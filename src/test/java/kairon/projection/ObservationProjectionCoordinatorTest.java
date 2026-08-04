package kairon.projection;

import kairon.behavior.bus.BehaviorGraphObservationProcessor;
import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.graph.BehaviorGraphApplyStatus;
import kairon.behavior.graph.BehaviorGraphProcessor;
import kairon.behavior.graph.BehaviorGraphQueryService;
import kairon.behavior.graph.BehaviorGraphService;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.GraphId;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.behavior.persistence.InMemoryBehaviorGraphStore;
import kairon.behavior.snapshot.BehaviorSituationCaptureStatus;
import kairon.behavior.snapshot.BehaviorSituationInconsistencyException;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.config.KaironConfiguration.BehaviorGraphConfiguration;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.ObservationDraft.SourcePosition;
import kairon.observation.ObservationPayload;
import kairon.observation.PublishedObservation;
import kairon.observation.bus.InProcessObservationBus;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser
        .CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser
        .ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.observation.journal.JournalObservationAdapter
        .JournalSourcePosition;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.source.ObservationSourceSignal
        .ObservationSourceSignalType;
import kairon.state.CurrentGameStateProjection;
import kairon.state.CurrentGameStateProjectionWriter;
import kairon.state.CurrentGameStateProjector;
import kairon.state.CurrentGameStateSnapshot;
import kairon.state.CurrentGameStateChangeSet;
import kairon.state.AppliedObservation;
import kairon.state.FlightMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ObservationProjectionCoordinatorTest {

    private static final ObservationSource SOURCE =
            new ObservationSource("projection-test", "coordinator");

    @Test
    void rawHandoffWaitsForStateAndTerminalGraphBeforeDownstream()
            throws Exception {
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountingStateWriter state = new CountingStateWriter(order);
        BlockingGraphProcessor graph = new BlockingGraphProcessor(order);
        ProjectedObservationBus projectedBus =
                new ProjectedObservationBus();
        projectedBus.subscribe(
                "llm-handoff",
                ignored -> order.add("llm-handoff")
        );
        ObservationProjectionCoordinator coordinator =
                new ObservationProjectionCoordinator(
                        state,
                        Optional.of(graph),
                        Optional.of((trigger, currentState, graphResult) -> {
                            order.add(
                                    "situation-" + trigger.busSequence()
                            );
                            return noActiveGraphSituation(
                                    trigger,
                                    currentState,
                                    graphResult
                            );
                        }),
                        projectedBus
                );
        InProcessObservationBus rawBus = new InProcessObservationBus();
        ObservationProjectionSubscriber.Subscription subscription =
                new ObservationProjectionSubscriber(coordinator)
                        .subscribeTo(rawBus);
        try {
            var receipt = rawBus.publish(draft(1, "first"))
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            assertEquals(
                    List.of(ObservationProjectionSubscriber.SUBSCRIBER_ID),
                    receipt.matchedSubscriberIds()
            );
            assertTrue(graph.entered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("state-1", "graph-start-1"), order);
            assertEquals(1, state.applyCount.get());

            graph.release.countDown();
            coordinator.awaitIdle().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            assertEquals(
                    List.of(
                            "state-1",
                            "graph-start-1",
                            "graph-end-1",
                            "situation-1",
                            "llm-handoff"
                    ),
                    order
            );
        } finally {
            rawBus.drainAndClose().toCompletableFuture().join();
            coordinator.shutdown().toCompletableFuture().join();
            subscription.close();
        }
    }

    @Test
    void capturedTrajectoryContainsTriggerOccurrenceOnlyAfterGraphReturns()
            throws Exception {
        JournalFixture journal = new JournalFixture();
        BehaviorGraphService service = new BehaviorGraphService(
                graphConfiguration(),
                new InMemoryBehaviorGraphStore()
        );
        BehaviorGraphObservationProcessor delegate =
                new BehaviorGraphObservationProcessor(service);
        CountDownLatch graphCommitted = new CountDownLatch(1);
        CountDownLatch releaseGraphReturn = new CountDownLatch(1);
        BehaviorGraphProcessor blocking = new BehaviorGraphProcessor() {
            @Override
            public BehaviorGraphApplyResult apply(
                    PublishedObservation<?> observation,
                    CurrentGameStateProjection stateProjection
            ) {
                BehaviorGraphApplyResult result =
                        delegate.apply(observation, stateProjection);
                if (observation.payload()
                        instanceof JournalEventObservation event
                        && event.raw().optionalEventType()
                        .filter("FSDJump"::equals)
                        .isPresent()) {
                    graphCommitted.countDown();
                    try {
                        if (!releaseGraphReturn.await(
                                2,
                                TimeUnit.SECONDS
                        )) {
                            throw new IllegalStateException(
                                    "test did not release graph return"
                            );
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                return result;
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
        List<ProjectedObservation> projected =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        ProjectedObservationBus projectedBus =
                new ProjectedObservationBus();
        projectedBus.subscribe("collector", projected::add);
        ObservationProjectionCoordinator coordinator =
                new ObservationProjectionCoordinator(
                        new CurrentGameStateProjector(),
                        Optional.of(blocking),
                        Optional.of(
                                new BehaviorGraphQueryService(service)
                        ),
                        projectedBus
                );
        try {
            coordinator.submit(journal.observation("""
                    {"timestamp":"2026-07-30T09:00:00Z",
                     "event":"LoadGame","FID":"F350",
                     "ShipID":35,"Ship":"cobra_mk_iv"}
                    """)).toCompletableFuture().join();
            CompletionStage<Void> jumpCompletion =
                    coordinator.submit(journal.observation("""
                            {"timestamp":"2026-07-30T09:00:01Z",
                             "event":"FSDJump","StarSystem":"Barrier",
                             "SystemAddress":3501}
                            """));

            assertTrue(graphCommitted.await(2, TimeUnit.SECONDS));
            assertEquals(1, projected.size());
            assertFalse(
                    jumpCompletion.toCompletableFuture().isDone()
            );

            releaseGraphReturn.countDown();
            jumpCompletion.toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            ProjectedObservation jump = projected.getLast();
            var episode = jump.behaviorSituation()
                    .activeEpisode()
                    .orElseThrow();
            assertEquals(1, episode.trajectory().size());
            assertEquals(
                    NormalizedEventType.SYSTEM_ENTRY,
                    episode.currentOccurrence().eventType()
            );
            assertEquals(
                    jump.graphResult().cursor()
                            .orElseThrow()
                            .occurrenceId(),
                    episode.currentOccurrence().occurrenceId()
            );
        } finally {
            releaseGraphReturn.countDown();
            coordinator.shutdown().toCompletableFuture().join();
        }
    }

    @Test
    void projectedObservationsRemainFifoAndStateIsAppliedExactlyOnce() {
        List<Long> graphOrder =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Long> downstreamOrder =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        CountingStateWriter state = new CountingStateWriter(
                new java.util.concurrent.CopyOnWriteArrayList<>()
        );
        BehaviorGraphProcessor graph = new BehaviorGraphProcessor() {
            @Override
            public BehaviorGraphApplyResult apply(
                    PublishedObservation<?> observation,
                    CurrentGameStateProjection stateProjection
            ) {
                graphOrder.add(observation.busSequence());
                return BehaviorGraphApplyResult.notApplicable(
                        observation.busSequence()
                );
            }

            @Override
            public void close() {
            }
        };
        ProjectedObservationBus projectedBus =
                new ProjectedObservationBus();
        projectedBus.subscribe(
                "async-llm-handoff",
                projected -> {
                    downstreamOrder.add(projected.busSequence());
                    new CompletableFuture<>();
                }
        );
        ObservationProjectionCoordinator coordinator =
                new ObservationProjectionCoordinator(
                        state,
                        Optional.of(graph),
                        Optional.of(
                                ObservationProjectionCoordinatorTest
                                        ::noActiveGraphSituation
                        ),
                        projectedBus
                );
        try {
            List<CompletableFuture<Void>> completions =
                    java.util.stream.LongStream.rangeClosed(1, 4)
                            .mapToObj(sequence -> coordinator.submit(
                                    observation(sequence, "event-" + sequence)
                            ).toCompletableFuture())
                            .toList();
            CompletableFuture.allOf(
                    completions.toArray(CompletableFuture[]::new)
            ).join();

            assertEquals(List.of(1L, 2L, 3L, 4L), graphOrder);
            assertEquals(List.of(1L, 2L, 3L, 4L), downstreamOrder);
            assertEquals(4, state.applyCount.get());
        } finally {
            coordinator.shutdown().toCompletableFuture().join();
        }
    }

    @Test
    void downstreamPublisherUsesRegistrationOrderAndIsolatesFailures() {
        List<String> calls = new ArrayList<>();
        ProjectedObservationBus bus = new ProjectedObservationBus();
        bus.subscribe("first", observation -> {
            calls.add("first");
            throw new IllegalStateException("expected subscriber failure");
        });
        bus.subscribe("second", observation -> calls.add("second"));

        PublishedObservation<TestPayload> trigger =
                observation(1, "publisher");
        BehaviorGraphApplyResult graphResult =
                BehaviorGraphApplyResult.disabled(trigger.busSequence());
        bus.publish(new ProjectedObservation(
                trigger,
                applied(trigger),
                CurrentGameStateChangeSet.between(
                        CurrentGameStateSnapshot.unknown(),
                        CurrentGameStateSnapshot.unknown()
                ),
                graphResult,
                BehaviorSituationSnapshot.unavailable(
                        graphResult,
                        BehaviorSituationCaptureStatus.GRAPH_DISABLED
                ),
                SemanticEnvelopeFactory.production().create(
                        trigger,
                        applied(trigger)
                )
        ));

        assertEquals(List.of("first", "second"), calls);
        bus.close();
    }

    @Test
    void disabledGraphStillPublishesPostEventStateAndFriendsTrigger() {
        JournalFixture journal = new JournalFixture();
        List<ProjectedObservation> projected =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        CurrentGameStateProjector state =
                new CurrentGameStateProjector();
        ProjectedObservationBus projectedBus =
                new ProjectedObservationBus();
        projectedBus.subscribe("collector", projected::add);
        ObservationProjectionCoordinator coordinator =
                new ObservationProjectionCoordinator(
                        state,
                        Optional.empty(),
                        Optional.empty(),
                        projectedBus
                );
        try {
            List<PublishedObservation<JournalEventObservation>> observations =
                    List.of(
                            journal.observation("""
                                    {"timestamp":"2026-07-30T10:00:00Z",
                                     "event":"LoadGame","FID":"F100",
                                     "ShipID":9,"Ship":"krait_mkii"}
                                    """),
                            journal.observation("""
                                    {"timestamp":"2026-07-30T10:00:01Z",
                                     "event":"Loadout","ShipID":9,
                                     "Ship":"krait_mkii","ShipName":"Caspian",
                                     "Modules":[]}
                                    """),
                            journal.observation("""
                                    {"timestamp":"2026-07-30T10:00:02Z",
                                     "event":"Location",
                                     "StarSystem":"Snapshot",
                                     "SystemAddress":5001,
                                     "Body":"Snapshot 4","BodyID":4,
                                     "Docked":false}
                                    """),
                            journal.observation("""
                                    {"timestamp":"2026-07-30T10:00:03Z",
                                     "event":"SupercruiseEntry",
                                     "StarSystem":"Snapshot",
                                     "SystemAddress":5001}
                                    """),
                            journal.observation("""
                                    {"timestamp":"2026-07-30T10:00:04Z",
                                     "event":"Friends","Status":"Online",
                                     "Name":"Wingmate"}
                                    """)
                    );
            observations.forEach(observation ->
                    coordinator.submit(observation)
                            .toCompletableFuture()
                            .join()
            );

            assertEquals(5, projected.size());
            assertEquals("F100",
                    projected.get(0).currentState().commanderFid());
            assertEquals("Caspian",
                    projected.get(1).currentState().shipName());
            assertEquals(5001L,
                    projected.get(2).currentState().systemAddress());
            assertEquals(FlightMode.SUPERCRUISE,
                    projected.get(3).currentState().flightMode());
            assertEquals(
                    projected.get(3).currentState(),
                    projected.get(4).currentState()
            );
            assertEquals(
                    "Friends",
                    ((JournalEventObservation) projected.get(4)
                            .trigger().payload())
                            .raw().optionalEventType().orElseThrow()
            );
            assertTrue(projected.stream().allMatch(item ->
                    item.graphResult().status()
                            == BehaviorGraphApplyStatus.DISABLED));
            assertTrue(projected.stream().allMatch(item ->
                    item.behaviorSituation().captureStatus()
                            == BehaviorSituationCaptureStatus
                            .GRAPH_DISABLED));
            assertEquals(FlightMode.UNKNOWN,
                    projected.getFirst().currentState().flightMode());
        } finally {
            coordinator.shutdown().toCompletableFuture().join();
        }
    }

    @Test
    void graphFailurePublishesDegradedResultAndNextObservationContinues() {
        JournalFixture journal = new JournalFixture();
        AtomicInteger calls = new AtomicInteger();
        BehaviorGraphProcessor graph = new BehaviorGraphProcessor() {
            @Override
            public BehaviorGraphApplyResult apply(
                    PublishedObservation<?> observation,
                    CurrentGameStateProjection stateProjection
            ) {
                if (calls.getAndIncrement() == 0) {
                    throw new IllegalStateException("expected graph failure");
                }
                return BehaviorGraphApplyResult.notApplicable(
                        observation.busSequence()
                );
            }

            @Override
            public void close() {
            }
        };
        List<ProjectedObservation> projected =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        ProjectedObservationBus projectedBus =
                new ProjectedObservationBus();
        projectedBus.subscribe("collector", projected::add);
        ObservationProjectionCoordinator coordinator =
                new ObservationProjectionCoordinator(
                        new CurrentGameStateProjector(),
                        Optional.of(graph),
                        Optional.of(
                                ObservationProjectionCoordinatorTest
                                        ::noActiveGraphSituation
                        ),
                        projectedBus
                );
        try {
            coordinator.submit(journal.observation("""
                    {"timestamp":"2026-07-30T11:00:00Z",
                     "event":"LoadGame","FID":"F200",
                     "ShipID":19,"Ship":"python"}
                    """)).toCompletableFuture().join();
            coordinator.submit(journal.observation("""
                    {"timestamp":"2026-07-30T11:00:01Z",
                     "event":"Friends","Status":"Online","Name":"A"}
                    """)).toCompletableFuture().join();

            assertEquals(2, projected.size());
            assertEquals(BehaviorGraphApplyStatus.FAILED,
                    projected.get(0).graphResult().status());
            assertEquals(
                    BehaviorSituationCaptureStatus.GRAPH_APPLY_FAILED,
                    projected.get(0).behaviorSituation().captureStatus()
            );
            assertEquals("F200",
                    projected.get(0).currentState().commanderFid());
            assertEquals(BehaviorGraphApplyStatus.NOT_APPLICABLE,
                    projected.get(1).graphResult().status());
            assertEquals(
                    BehaviorSituationCaptureStatus.NO_ACTIVE_GRAPH,
                    projected.get(1).behaviorSituation().captureStatus()
            );
            assertEquals(2, calls.get());
        } finally {
            coordinator.shutdown().toCompletableFuture().join();
        }
    }

    @Test
    void stateFailurePublishesNoFalsePostStateButLaterInputIsProcessed() {
        AtomicInteger calls = new AtomicInteger();
        CurrentGameStateProjector delegate =
                new CurrentGameStateProjector();
        CurrentGameStateProjectionWriter state =
                new CurrentGameStateProjectionWriter() {
                    @Override
                    public CurrentGameStateProjection applyAndCapture(
                            PublishedObservation<?> observation
                    ) {
                        if (calls.getAndIncrement() == 0) {
                            throw new IllegalStateException(
                                    "expected state failure"
                            );
                        }
                        return delegate.applyAndCapture(observation);
                    }

                    @Override
                    public CurrentGameStateSnapshot currentSnapshot() {
                        return delegate.currentSnapshot();
                    }
                };
        List<ProjectedObservation> projected =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        ProjectedObservationBus projectedBus =
                new ProjectedObservationBus();
        projectedBus.subscribe("collector", projected::add);
        ObservationProjectionCoordinator coordinator =
                new ObservationProjectionCoordinator(
                        state,
                        Optional.empty(),
                        Optional.empty(),
                        projectedBus
                );
        try {
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> coordinator.submit(observation(1, "bad"))
                            .toCompletableFuture()
                            .join()
            );
            assertInstanceOf(
                    IllegalStateException.class,
                    failure.getCause()
            );
            coordinator.submit(observation(2, "good"))
                    .toCompletableFuture()
                    .join();

            assertEquals(2, calls.get());
            assertEquals(1, projected.size());
            assertEquals(2, projected.getFirst().busSequence());
        } finally {
            assertThrows(
                    CompletionException.class,
                    () -> coordinator.shutdown()
                            .toCompletableFuture()
                            .join()
            );
        }
    }

    @Test
    void captureFailuresAreExplicitAndDoNotStopFifoProcessing() {
        AtomicInteger captures = new AtomicInteger();
        BehaviorGraphProcessor graph = new BehaviorGraphProcessor() {
            @Override
            public BehaviorGraphApplyResult apply(
                    PublishedObservation<?> observation,
                    CurrentGameStateProjection stateProjection
            ) {
                return BehaviorGraphApplyResult.notApplicable(
                        observation.busSequence()
                );
            }

            @Override
            public void close() {
            }
        };
        List<ProjectedObservation> projected =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        ProjectedObservationBus projectedBus =
                new ProjectedObservationBus();
        projectedBus.subscribe("collector", projected::add);
        ObservationProjectionCoordinator coordinator =
                new ObservationProjectionCoordinator(
                        new CurrentGameStateProjector(),
                        Optional.of(graph),
                        Optional.of((trigger, currentState, graphResult) -> {
                            return switch (captures.getAndIncrement()) {
                                case 0 -> throw new
                                        BehaviorSituationInconsistencyException(
                                                "expected mismatch"
                                        );
                                case 1 -> throw new IllegalStateException(
                                        "expected capture failure"
                                );
                                default -> noActiveGraphSituation(
                                        trigger,
                                        currentState,
                                        graphResult
                                );
                            };
                        }),
                        projectedBus
                );
        try {
            coordinator.submit(observation(1, "inconsistent"))
                    .toCompletableFuture()
                    .join();
            coordinator.submit(observation(2, "failed"))
                    .toCompletableFuture()
                    .join();
            coordinator.submit(observation(3, "recovered"))
                    .toCompletableFuture()
                    .join();

            assertEquals(
                    List.of(
                            BehaviorSituationCaptureStatus.INCONSISTENT,
                            BehaviorSituationCaptureStatus.SNAPSHOT_FAILED,
                            BehaviorSituationCaptureStatus.NO_ACTIVE_GRAPH
                    ),
                    projected.stream()
                            .map(item -> item.behaviorSituation()
                                    .captureStatus())
                            .toList()
            );
            assertEquals(
                    List.of(1L, 2L, 3L),
                    projected.stream()
                            .map(ProjectedObservation::busSequence)
                            .toList()
            );
        } finally {
            coordinator.shutdown().toCompletableFuture().join();
        }
    }

    @Test
    void realGraphKeepsEventSpecificContextAndCompletesReplayInOrder() {
        JournalFixture journal = new JournalFixture();
        InMemoryBehaviorGraphStore store =
                new InMemoryBehaviorGraphStore();
        BehaviorGraphService service = new BehaviorGraphService(
                graphConfiguration(),
                store
        );
        BehaviorGraphQueryService query =
                new BehaviorGraphQueryService(service);
        ProjectedObservationBus projectedBus =
                new ProjectedObservationBus();
        List<ProjectedObservation> projected =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        projectedBus.subscribe("collector", projected::add);
        ObservationProjectionCoordinator coordinator =
                new ObservationProjectionCoordinator(
                        new CurrentGameStateProjector(),
                        Optional.of(
                                new BehaviorGraphObservationProcessor(service)
                        ),
                        Optional.of(query),
                        projectedBus
                );
        GraphId graphId = new GraphId("F300", 29);
        try {
            coordinator.submit(journal.observation("""
                    {"timestamp":"2026-07-30T12:00:00Z",
                     "event":"LoadGame","FID":"F300",
                     "ShipID":29,"Ship":"mandalay"}
                    """)).toCompletableFuture().join();
            coordinator.submit(journal.observation("""
                    {"timestamp":"2026-07-30T12:00:01Z",
                     "event":"FSDJump","StarSystem":"Context",
                     "SystemAddress":8001}
                    """)).toCompletableFuture().join();
            coordinator.submit(journal.observation("""
                    {"timestamp":"2026-07-30T12:00:02Z",
                     "event":"ApproachBody","StarSystem":"Context",
                     "SystemAddress":8001,
                     "Body":"Context 2","BodyID":2}
                    """)).toCompletableFuture().join();
            coordinator.submit(journal.observation("""
                    {"timestamp":"2026-07-30T12:00:03Z",
                     "event":"LeaveBody","StarSystem":"Context",
                     "SystemAddress":8001,
                     "Body":"Context 2","BodyID":2}
                    """)).toCompletableFuture().join();
            PublishedObservation<ObservationSourceSignal> exhausted =
                    journal.replayExhausted();
            coordinator.submit(exhausted).toCompletableFuture().join();
            coordinator.awaitIdle().toCompletableFuture().join();

            EventOccurrence leave = service.episodes(graphId).stream()
                    .flatMap(episode -> episode.timeline().stream())
                    .filter(occurrence -> occurrence.eventType()
                            == NormalizedEventType.LEAVE_BODY)
                    .findFirst()
                    .orElseThrow();
            assertEquals(2L, leave.context().bodyId());
            assertNull(projected.get(3).currentState().bodyId());
            assertTrue(service.activeEpisode(graphId).isEmpty());
            assertEquals(
                    BehaviorGraphApplyStatus.APPLIED,
                    projected.getLast().graphResult().status()
            );
            assertEquals(
                    BehaviorSituationCaptureStatus.NO_ACTIVE_EPISODE,
                    projected.getLast().behaviorSituation().captureStatus()
            );
            assertEquals(exhausted,
                    projected.getLast().trigger());
            assertEquals(
                    List.of(1L, 2L, 3L, 4L, 5L),
                    projected.stream()
                            .map(ProjectedObservation::busSequence)
                            .toList()
            );
        } finally {
            coordinator.shutdown().toCompletableFuture().join();
        }
    }

    private static BehaviorSituationSnapshot noActiveGraphSituation(
            PublishedObservation<?> trigger,
            CurrentGameStateSnapshot currentState,
            BehaviorGraphApplyResult graphResult
    ) {
        assertEquals(trigger.busSequence(), graphResult.busSequence());
        return BehaviorSituationSnapshot.unavailable(
                graphResult,
                BehaviorSituationCaptureStatus.NO_ACTIVE_GRAPH
        );
    }

    private static BehaviorGraphConfiguration graphConfiguration() {
        return new BehaviorGraphConfiguration(
                true,
                Path.of("target", "projection-test-unused"),
                Duration.ofDays(30),
                2.0,
                50,
                false
        );
    }

    private static ObservationDraft<TestPayload> draft(
            long sourceSequence,
            String value
    ) {
        return new ObservationDraft<>(
                "draft-" + sourceSequence,
                SOURCE,
                new TestSourcePosition(sourceSequence),
                Optional.empty(),
                Instant.EPOCH,
                ObservationCaptureMode.LIVE,
                "test/v1",
                new TestPayload(value)
        );
    }

    private static PublishedObservation<TestPayload> observation(
            long busSequence,
            String value
    ) {
        ObservationDraft<TestPayload> draft = draft(busSequence, value);
        return new PublishedObservation<>(
                draft.observationId(),
                busSequence,
                draft.source(),
                draft.sourcePosition(),
                draft.sourceTime(),
                draft.observedAt(),
                draft.captureMode(),
                draft.schemaVersion(),
                draft.payload()
        );
    }


    private static AppliedObservation applied(
            PublishedObservation<?> observation
    ) {
        return AppliedObservation.of(
                observation,
                CurrentGameStateSnapshot.unknown(),
                CurrentGameStateSnapshot.unknown(),
                CurrentGameStateSnapshot.unknown(),
                List.of()
        );
    }

    private record TestPayload(String value)
            implements ObservationPayload {
    }

    private record TestSourcePosition(long sequence)
            implements SourcePosition {
    }

    private static final class CountingStateWriter
            implements CurrentGameStateProjectionWriter {

        private final CurrentGameStateProjector delegate =
                new CurrentGameStateProjector();
        private final List<String> order;
        private final AtomicInteger applyCount = new AtomicInteger();

        private CountingStateWriter(List<String> order) {
            this.order = order;
        }

        @Override
        public CurrentGameStateProjection applyAndCapture(
                PublishedObservation<?> observation
        ) {
            applyCount.incrementAndGet();
            order.add("state-" + observation.busSequence());
            return delegate.applyAndCapture(observation);
        }

        @Override
        public CurrentGameStateSnapshot currentSnapshot() {
            return delegate.currentSnapshot();
        }
    }

    private static final class BlockingGraphProcessor
            implements BehaviorGraphProcessor {

        private final List<String> order;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingGraphProcessor(List<String> order) {
            this.order = order;
        }

        @Override
        public BehaviorGraphApplyResult apply(
                PublishedObservation<?> observation,
                CurrentGameStateProjection stateProjection
        ) {
            order.add("graph-start-" + observation.busSequence());
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "test did not release graph processor"
                    );
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            order.add("graph-end-" + observation.busSequence());
            return BehaviorGraphApplyResult.notApplicable(
                    observation.busSequence()
            );
        }

        @Override
        public void close() {
        }
    }

    private static final class JournalFixture {

        private static final String BASENAME =
                "Journal.projection-test.log";
        private final JournalLineParser parser = new JournalLineParser();
        private final JournalObservationAdapter adapter =
                new JournalObservationAdapter(SOURCE);
        private long busSequence;
        private long sourceOffset;

        private PublishedObservation<JournalEventObservation> observation(
                String rawJson
        ) {
            byte[] bytes = rawJson.strip()
                    .getBytes(StandardCharsets.UTF_8);
            ParsedJournalRecord parsed = assertInstanceOf(
                    ParsedJournalRecord.class,
                    parser.parse(new CompleteJournalRecord(
                            BASENAME,
                            sourceOffset,
                            bytes
                    ))
            );
            sourceOffset += bytes.length + 1L;
            ObservationDraft<JournalEventObservation> draft =
                    adapter.adapt(
                            parsed,
                            ObservationCaptureMode.REPLAY,
                            parsed.optionalJournalTimestamp()
                                    .orElse(Instant.EPOCH)
                    );
            return publish(draft);
        }

        private PublishedObservation<ObservationSourceSignal>
                replayExhausted() {
            return new PublishedObservation<>(
                    "replay-exhausted",
                    ++busSequence,
                    SOURCE,
                    new JournalSourcePosition(
                            BASENAME,
                            sourceOffset
                    ),
                    Optional.empty(),
                    Instant.EPOCH,
                    ObservationCaptureMode.REPLAY,
                    ObservationSourceSignal.SCHEMA_VERSION,
                    new ObservationSourceSignal(
                            ObservationSourceSignalType
                                    .REPLAY_SOURCE_EXHAUSTED
                    )
            );
        }

        private <T extends ObservationPayload> PublishedObservation<T>
                publish(ObservationDraft<T> draft) {
            return new PublishedObservation<>(
                    draft.observationId(),
                    ++busSequence,
                    draft.source(),
                    draft.sourcePosition(),
                    draft.sourceTime(),
                    draft.observedAt(),
                    draft.captureMode(),
                    draft.schemaVersion(),
                    draft.payload()
            );
        }
    }
}
