package kairon.observation.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.ObservationDraft.SourcePosition;
import kairon.observation.ObservationPayload;
import kairon.observation.PublishedObservation;
import kairon.observation.bus.ObservationBus.ObservationHandler;
import kairon.observation.bus.ObservationBus.ObservationSubscription;
import kairon.observation.bus.ObservationBus.PublishReceipt;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.event.travel.Docked;
import kairon.observation.journal.event.travel.FSDJump;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InProcessObservationBusTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void deliversTheSameObservationsToTypedSubscribersInSequenceAndRegistrationOrder()
            throws Exception {
        InProcessObservationBus bus = new InProcessObservationBus();
        List<String> calls = new CopyOnWriteArrayList<>();
        List<PublishedObservation<?>> firstPublicationInstances =
                new CopyOnWriteArrayList<>();

        bus.subscribe("llm", TestPayload.class, observation -> {
            calls.add("llm:" + observation.busSequence());
            if (observation.busSequence() == 1) {
                firstPublicationInstances.add(observation);
            }
        });
        bus.subscribe("diagnostic", ObservationPayload.class, observation -> {
            calls.add("diagnostic:" + observation.busSequence());
            if (observation.busSequence() == 1) {
                firstPublicationInstances.add(observation);
            }
        });
        bus.subscribe("other-type", OtherPayload.class, observation -> calls.add("other"));

        PublishReceipt first = await(bus.publish(draft("first", 0)));
        PublishReceipt second = await(bus.publish(draft("second", 1)));

        assertEquals(1, first.busSequence());
        assertEquals(2, second.busSequence());
        assertEquals(List.of("llm", "diagnostic"), first.matchedSubscriberIds());
        assertEquals(List.of("llm", "diagnostic"), second.matchedSubscriberIds());
        assertEquals(
                List.of("llm:1", "diagnostic:1", "llm:2", "diagnostic:2"),
                calls
        );
        assertEquals(2, firstPublicationInstances.size());
        assertSame(firstPublicationInstances.get(0), firstPublicationInstances.get(1));

        await(bus.drainAndClose());

        InProcessObservationBus journalBus = new InProcessObservationBus();
        List<Long> baseDeliveries = new CopyOnWriteArrayList<>();
        List<Long> concreteDeliveries = new CopyOnWriteArrayList<>();
        journalBus.subscribe(
                "journal-base",
                JournalEventObservation.class,
                observation -> baseDeliveries.add(observation.busSequence())
        );
        journalBus.subscribe(
                "fsd-jump",
                FSDJump.class,
                observation -> concreteDeliveries.add(observation.busSequence())
        );

        PublishReceipt fsdJump = await(journalBus.publish(journalDraft(
                "typed-fsd-jump",
                2,
                new FSDJump(raw("FSDJump"))
        )));
        PublishReceipt docked = await(journalBus.publish(journalDraft(
                "typed-docked",
                3,
                new Docked(raw("Docked"))
        )));

        assertEquals(List.of("journal-base", "fsd-jump"), fsdJump.matchedSubscriberIds());
        assertEquals(List.of("journal-base"), docked.matchedSubscriberIds());
        assertEquals(List.of(1L, 2L), baseDeliveries);
        assertEquals(List.of(1L), concreteDeliveries);
        await(journalBus.drainAndClose());
    }

    @Test
    void isolatesHandlerExceptionsAndReportsThemInATransportReceipt() throws Exception {
        for (boolean brokenFirst : List.of(true, false)) {
            InProcessObservationBus bus = new InProcessObservationBus();
            List<Long> healthyDeliveries = new CopyOnWriteArrayList<>();
            ObservationHandler<TestPayload> broken = observation -> {
                throw new IllegalStateException("subscriber handoff failure");
            };
            ObservationHandler<TestPayload> healthy =
                    observation -> healthyDeliveries.add(observation.busSequence());

            if (brokenFirst) {
                bus.subscribe("broken", TestPayload.class, broken);
                bus.subscribe("healthy", TestPayload.class, healthy);
            } else {
                bus.subscribe("healthy", TestPayload.class, healthy);
                bus.subscribe("broken", TestPayload.class, broken);
            }

            PublishReceipt receipt = await(bus.publish(draft(
                    "isolated-" + brokenFirst,
                    0
            )));

            assertEquals(
                    brokenFirst
                            ? List.of("broken", "healthy")
                            : List.of("healthy", "broken"),
                    receipt.matchedSubscriberIds()
            );
            assertEquals(List.of("broken"), receipt.failedSubscriberIds());
            assertEquals(List.of(1L), healthyDeliveries);
            await(bus.drainAndClose());
        }
    }

    @Test
    void enforcesLifetimeDuplicateIdsAndSynchronousSubscriptionClosure() throws Exception {
        InProcessObservationBus bus = new InProcessObservationBus();
        List<String> delivered = new CopyOnWriteArrayList<>();
        ObservationSubscription subscription = bus.subscribe(
                "one-life",
                TestPayload.class,
                observation -> delivered.add(observation.observationId())
        );

        await(bus.publish(draft("before-close", 0)));
        subscription.close();
        subscription.close();
        PublishReceipt afterClose = await(bus.publish(draft("after-close", 1)));

        assertFalse(subscription.isActive());
        assertEquals(List.of("before-close"), delivered);
        assertEquals(List.of(), afterClose.matchedSubscriberIds());
        assertThrows(
                IllegalArgumentException.class,
                () -> bus.subscribe("one-life", TestPayload.class, observation -> {
                })
        );

        await(bus.drainAndClose());
    }

    @Test
    void queuesReentrantPublicationWithoutRecursiveHandlerInvocation() throws Exception {
        InProcessObservationBus bus = new InProcessObservationBus();
        AtomicReference<CompletionStage<PublishReceipt>> nestedStage =
                new AtomicReference<>();
        AtomicInteger currentDepth = new AtomicInteger();
        AtomicInteger maximumDepth = new AtomicInteger();
        List<String> delivered = new CopyOnWriteArrayList<>();

        bus.subscribe("reentrant", TestPayload.class, observation -> {
            int depth = currentDepth.incrementAndGet();
            maximumDepth.accumulateAndGet(depth, Math::max);
            try {
                delivered.add(
                        observation.observationId() + ":" + observation.busSequence()
                );
                if (observation.observationId().equals("outer")) {
                    nestedStage.set(bus.publish(draft("inner", 1)));
                }
            } finally {
                currentDepth.decrementAndGet();
            }
        });

        PublishReceipt outer = await(bus.publish(draft("outer", 0)));
        assertNotNull(nestedStage.get());
        PublishReceipt inner = await(nestedStage.get());

        assertEquals(1, outer.busSequence());
        assertEquals(2, inner.busSequence());
        assertEquals(List.of("outer:1", "inner:2"), delivered);
        assertEquals(1, maximumDepth.get());

        await(bus.drainAndClose());
    }

    @Test
    void drainsAcceptedWorkDeactivatesSubscriptionsAndRejectsLaterPublications()
            throws Exception {
        InProcessObservationBus bus = new InProcessObservationBus();
        CountDownLatch firstHandlerStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstHandler = new CountDownLatch(1);
        List<Long> deliveredSequences = new CopyOnWriteArrayList<>();

        ObservationSubscription subscription = bus.subscribe(
                "drain",
                TestPayload.class,
                observation -> {
                    if (observation.observationId().equals("first")) {
                        firstHandlerStarted.countDown();
                        awaitLatch(releaseFirstHandler);
                    }
                    deliveredSequences.add(observation.busSequence());
                }
        );

        CompletionStage<PublishReceipt> first = bus.publish(draft("first", 0));
        assertTrue(firstHandlerStarted.await(5, TimeUnit.SECONDS));
        CompletionStage<PublishReceipt> second = bus.publish(draft("second", 1));
        CompletionStage<Void> drain = bus.drainAndClose();
        CompletionStage<PublishReceipt> rejected = bus.publish(draft("rejected", 2));

        try {
            ExecutionException rejection = assertThrows(
                    ExecutionException.class,
                    () -> rejected.toCompletableFuture().get(5, TimeUnit.SECONDS)
            );
            assertInstanceOf(IllegalStateException.class, rejection.getCause());
        } finally {
            releaseFirstHandler.countDown();
        }

        assertEquals(1, await(first).busSequence());
        assertEquals(2, await(second).busSequence());
        await(drain);
        assertSame(drain, bus.drainAndClose());
        assertEquals(List.of(1L, 2L), deliveredSequences);
        assertFalse(subscription.isActive());
        bus.close();
    }

    private static ObservationDraft<TestPayload> draft(String observationId, long position) {
        return new ObservationDraft<>(
                observationId,
                new ObservationSource("test", "test-source"),
                new TestPosition(position),
                Optional.empty(),
                Instant.ofEpochSecond(position),
                ObservationCaptureMode.LIVE,
                "test-payload/v1",
                new TestPayload(observationId)
        );
    }

    private static ObservationDraft<JournalEventObservation> journalDraft(
            String observationId,
            long position,
            JournalEventObservation payload
    ) {
        return new ObservationDraft<>(
                observationId,
                new ObservationSource("journal-test", "journal-test-source"),
                new TestPosition(position),
                Optional.empty(),
                Instant.ofEpochSecond(position),
                ObservationCaptureMode.REPLAY,
                JournalEventObservation.SCHEMA_VERSION,
                payload
        );
    }

    private static RawJournalData raw(String eventType) {
        String rawJson = "{\"event\":\"" + eventType + "\",\"future\":true}";
        try {
            return new RawJournalData(
                    rawJson,
                    JSON.readTree(rawJson),
                    Optional.of(eventType),
                    Optional.empty()
            );
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test handler interrupted", interruption);
        }
    }

    private record TestPayload(String value) implements ObservationPayload {
    }

    private record OtherPayload(String value) implements ObservationPayload {
    }

    private record TestPosition(long value) implements SourcePosition {
    }
}
