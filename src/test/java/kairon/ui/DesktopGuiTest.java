package kairon.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.llm.ObserverResponseValidator.Decision;
import kairon.llm.ObserverResponseValidator.ValidatedObserverResponse;
import kairon.llm.ObserverResponseValidator.Status;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.bus.InProcessObservationBus;
import kairon.observation.bus.ObservationBus.ObservationSubscription;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.JournalObservationAdapter.JournalSourcePosition;
import kairon.observation.journal.UnknownJournalEvent;
import kairon.observation.journal.event.session.Music;
import kairon.observer.ObserverTurnListener.ObservationEffect;
import kairon.observer.ObserverTurnListener.ObservationEffectChanged;
import kairon.observer.ObserverTurnListener.DecisionResolved;
import kairon.observer.ObserverTurnListener.TurnCompleted;
import kairon.output.CommentSink.CommentDeliveryResult;
import kairon.output.CommentSink.ConsoleOutcome;
import kairon.output.CommentSink.SpeechDeliveryResult;
import kairon.output.CommentSink.SpeechDescriptor;
import kairon.system.SystemRegistrySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DesktopGuiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObservationSource SOURCE = new ObservationSource(
            "elite-dangerous-journal",
            "gui-test"
    );

    @Test
    void subscriberForwardsAllJournalModesAndRawDataInBusOrder()
            throws Exception {
        RecordingHub hub = new RecordingHub();
        InProcessObservationBus bus = new InProcessObservationBus();
        ObservationSubscription gui =
                new DesktopUiSubscriber(hub).subscribeTo(bus);
        AtomicInteger independentDeliveries = new AtomicInteger();
        ObservationSubscription independent = bus.subscribe(
                "independent-journal-consumer",
                JournalEventObservation.class,
                ignored -> independentDeliveries.incrementAndGet()
        );
        try {
            List<ObservationDraft<JournalEventObservation>> drafts = List.of(
                    draft(1, ObservationCaptureMode.BOOTSTRAP, "Music"),
                    draft(2, ObservationCaptureMode.LIVE, "FutureEvent"),
                    draft(3, ObservationCaptureMode.REPLAY, "Music")
            );
            for (ObservationDraft<JournalEventObservation> draft : drafts) {
                bus.publish(draft).toCompletableFuture().join();
            }

            assertEquals(3, independentDeliveries.get());
            assertEquals(
                    List.of(1L, 2L, 3L),
                    hub.observations.stream()
                            .map(KaironGuiHub.ObservationView::busSequence)
                            .toList()
            );
            assertEquals(
                    List.of("BOOTSTRAP", "LIVE", "REPLAY"),
                    hub.observations.stream()
                            .map(KaironGuiHub.ObservationView::captureMode)
                            .toList()
            );
            assertEquals(
                    List.of("Music", "FutureEvent", "Music"),
                    hub.observations.stream()
                            .map(KaironGuiHub.ObservationView::eventType)
                            .toList()
            );
            assertEquals(
                    drafts.stream()
                            .map(draft -> draft.payload().raw().rawJson())
                            .toList(),
                    hub.observations.stream()
                            .map(KaironGuiHub.ObservationView::rawJson)
                            .toList()
            );
        } finally {
            gui.close();
            independent.close();
            bus.drainAndClose().toCompletableFuture().join();
        }
    }

    @Test
    void observerBridgePreservesSilentCommentAndDeliveryFacts() {
        RecordingHub hub = new RecordingHub();
        DesktopObserverTurnListener bridge =
                new DesktopObserverTurnListener(hub);

        bridge.onObservationEffectChanged(new ObservationEffectChanged(
                "observation-7",
                7L,
                Instant.parse("2026-07-29T08:59:59Z"),
                ObservationEffect.NEW_IN_FLIGHT,
                2L
        ));
        bridge.onDecisionResolved(new DecisionResolved(
                1L,
                Instant.parse("2026-07-29T09:00:00Z"),
                3,
                List.of(5L, 6L, 7L),
                new ValidatedObserverResponse(
                        Status.VALID,
                        Decision.SILENT,
                        null,
                        List.of(),
                        null
                ),
                "{\"decision\":\"SILENT\"}",
                120L
        ));

        String commentRaw = "{\"decision\":\"COMMENT\","
                + "\"comment\":\"Course is clear.\"}";
        bridge.onDecisionResolved(new DecisionResolved(
                2L,
                Instant.parse("2026-07-29T09:00:01Z"),
                2,
                List.of(7L),
                new ValidatedObserverResponse(
                        Status.VALID,
                        Decision.COMMENT,
                        "Course is clear.",
                        List.of(),
                        null
                ),
                commentRaw,
                240L
        ));
        SpeechDescriptor speech = SpeechDescriptor.disabled(null, null);
        bridge.onTurnCompleted(new TurnCompleted(
                2L,
                Instant.parse("2026-07-29T09:00:02Z"),
                new CommentDeliveryResult(
                        speech,
                        ConsoleOutcome.DELIVERED,
                        SpeechDeliveryResult.notAttempted(speech)
                ),
                "Course is clear."
        ));

        assertEquals(2, hub.decisions.size());
        assertEquals(1, hub.effects.size());
        assertEquals(
                "observation-7",
                hub.effects.getFirst().observationId()
        );
        assertEquals(7L, hub.effects.getFirst().busSequence());
        assertEquals("NEW_IN_FLIGHT", hub.effects.getFirst().effect());
        assertEquals(2L, hub.effects.getFirst().turnSequence());
        assertEquals("SILENT", hub.decisions.getFirst().decision());
        assertNull(hub.decisions.getFirst().text());
        assertEquals("COMMENT", hub.decisions.getLast().decision());
        assertEquals("Course is clear.", hub.decisions.getLast().text());
        assertEquals(
                List.of(7L),
                hub.decisions.getLast().triggerBusSequences(),
                "the GUI is shown the batch, taken from the turn"
        );
        assertEquals(
                List.of(5L, 6L, 7L),
                hub.decisions.getFirst().triggerBusSequences(),
                "a silence attributes nothing, but still came from a batch"
        );
        assertEquals(commentRaw, hub.decisions.getLast().rawModelOutput());
        assertEquals(1, hub.completions.size());
        assertTrue(hub.completions.getFirst().deliveredForHistory());
        assertEquals(
                "Course is clear.",
                hub.completions.getFirst().deliveredComment()
        );
    }

    private static ObservationDraft<JournalEventObservation> draft(
            long sequence,
            ObservationCaptureMode mode,
            String eventType
    ) throws Exception {
        String rawJson = "{\"timestamp\":\"2026-07-29T09:00:0"
                + sequence
                + "Z\",\"event\":\""
                + eventType
                + "\",\"sequence\":"
                + sequence
                + '}';
        JsonNode parsed = JSON.readTree(rawJson);
        Instant sourceTime = Instant.parse(
                parsed.path("timestamp").textValue()
        );
        RawJournalData raw = new RawJournalData(
                rawJson,
                parsed,
                Optional.of(eventType),
                Optional.of(sourceTime)
        );
        JournalEventObservation payload = "Music".equals(eventType)
                ? new Music(raw)
                : new UnknownJournalEvent(raw);
        return new ObservationDraft<>(
                "gui-observation-" + sequence,
                SOURCE,
                new JournalSourcePosition(
                        "Journal.gui-test.log",
                        sequence * 100L
                ),
                Optional.of(sourceTime),
                Instant.parse("2026-07-29T09:01:00Z"),
                mode,
                JournalEventObservation.SCHEMA_VERSION,
                payload
        );
    }

    private static final class RecordingHub implements KaironGuiHub {

        private final List<ObservationView> observations =
                new CopyOnWriteArrayList<>();
        private final List<ObservationEffectView> effects =
                new CopyOnWriteArrayList<>();
        private final List<ModelDecisionView> decisions =
                new CopyOnWriteArrayList<>();
        private final List<ModelCompletionView> completions =
                new CopyOnWriteArrayList<>();
        private final List<SystemRegistrySnapshot>
                registrySnapshots = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> closeRequested =
                new CompletableFuture<>();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void start() {
        }

        @Override
        public void postObservation(ObservationView observation) {
            observations.add(observation);
        }

        @Override
        public void postObservationEffect(ObservationEffectView effect) {
            effects.add(effect);
        }

        @Override
        public void postModelDecision(ModelDecisionView decision) {
            decisions.add(decision);
        }

        @Override
        public void postSystemRegistry(
                SystemRegistrySnapshot snapshot
        ) {
            registrySnapshots.add(snapshot);
        }

        @Override
        public void postModelCompletion(ModelCompletionView completion) {
            completions.add(completion);
        }

        @Override
        public CompletionStage<Void> closeRequested() {
            return closeRequested;
        }

        @Override
        public void close() {
        }
    }
}
