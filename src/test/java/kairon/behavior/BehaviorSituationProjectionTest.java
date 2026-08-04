package kairon.behavior;

import kairon.behavior.bus.BehaviorGraphObservationProcessor;
import kairon.behavior.context.BehaviorContextAdapter;
import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.graph.BehaviorGraphApplyStatus;
import kairon.behavior.graph.BehaviorGraphChangeSet;
import kairon.behavior.graph.BehaviorGraphQueryService;
import kairon.behavior.graph.BehaviorGraphService;
import kairon.behavior.model.EventTypeNode;
import kairon.behavior.model.EpisodeCompletionReason;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.NextEventPrediction;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.behavior.persistence.InMemoryBehaviorGraphStore;
import kairon.behavior.snapshot.ActiveEpisodeSituation;
import kairon.behavior.snapshot.BehaviorSituationCaptureStatus;
import kairon.behavior.snapshot.BehaviorSituationInconsistencyException;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.behavior.snapshot.SituationNextEventPrediction;
import kairon.config.KaironConfiguration.BehaviorGraphConfiguration;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser
        .CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser
        .ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.projection.ObservationProjectionCoordinator;
import kairon.projection.ProjectedObservation;
import kairon.projection.ProjectedObservationBus;
import kairon.state.CurrentGameStateProjector;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BehaviorSituationProjectionTest {

    private static final GraphId GRAPH_ID = new GraphId("F500", 42);

    @Test
    void snapshotIsPostApplyAndRemainsImmutableAcrossLaterEvents() {
        try (Harness harness = new Harness()) {
            ProjectedObservation beforeIdentity = harness.accept("""
                    {"timestamp":"2026-07-30T10:00:00Z",
                     "event":"Friends","Status":"Online","Name":"A"}
                    """);
            assertEquals(
                    BehaviorGraphApplyStatus.NO_GRAPH_ID,
                    beforeIdentity.graphResult().status()
            );
            assertEquals(
                    BehaviorSituationCaptureStatus.NO_GRAPH_ID,
                    beforeIdentity.behaviorSituation().captureStatus()
            );
            assertTrue(
                    beforeIdentity.behaviorSituation()
                            .activeEpisode()
                            .isEmpty()
            );

            harness.identify();
            ProjectedObservation jump = harness.accept("""
                    {"timestamp":"2026-07-30T10:00:02Z",
                     "event":"FSDJump","StarSystem":"Snapshot A",
                     "SystemAddress":1001}
                    """);
            ActiveEpisodeSituation root =
                    active(jump.behaviorSituation());
            assertEquals(
                    List.of(NormalizedEventType.SYSTEM_ENTRY),
                    eventTypes(root)
            );
            assertEquals(
                    NormalizedEventType.SYSTEM_ENTRY,
                    root.currentOccurrence().eventType()
            );

            ProjectedObservation location = harness.accept("""
                    {"timestamp":"2026-07-30T10:00:03Z",
                     "event":"Location","StarSystem":"Snapshot A",
                     "SystemAddress":1001,"Docked":false}
                    """);
            BehaviorSituationSnapshot saved =
                    location.behaviorSituation();
            ActiveEpisodeSituation savedEpisode = active(saved);
            List<?> savedTrajectory = savedEpisode.trajectory();
            Map<?, ?> savedCounts = savedEpisode.occurrenceCounts();

            ProjectedObservation supercruise = harness.accept("""
                    {"timestamp":"2026-07-30T10:00:04Z",
                     "event":"SupercruiseEntry",
                     "StarSystem":"Snapshot A",
                     "SystemAddress":1001}
                    """);
            ActiveEpisodeSituation updated =
                    active(supercruise.behaviorSituation());

            assertEquals(
                    List.of(NormalizedEventType.SYSTEM_ENTRY),
                    eventTypes(savedEpisode)
            );
            assertEquals(1, savedTrajectory.size());
            assertEquals(
                    Map.of(NormalizedEventType.SYSTEM_ENTRY, 1L),
                    savedCounts
            );
            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.SUPERCRUISE_ENTRY
                    ),
                    eventTypes(updated)
            );
            assertTrue(
                    updated.graphVersion() > savedEpisode.graphVersion()
            );
            assertNotEquals(
                    savedEpisode.currentOccurrence().occurrenceId(),
                    updated.currentOccurrence().occurrenceId()
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> savedEpisode.trajectory().clear()
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> savedEpisode.occurrenceCounts().clear()
            );

            ProjectedObservation commander = harness.accept("""
                    {"timestamp":"2026-07-30T10:00:05Z",
                     "event":"Commander","FID":"F501","Name":"Other"}
                    """);
            assertEquals(
                    BehaviorGraphApplyStatus.NO_GRAPH_ID,
                    commander.graphResult().status()
            );
            assertEquals(
                    BehaviorSituationCaptureStatus.NO_GRAPH_ID,
                    commander.behaviorSituation().captureStatus()
            );
            assertTrue(
                    commander.behaviorSituation().activeEpisode().isEmpty()
            );
        }
    }

    @Test
    void newEpisodeExcludesCompletedTrajectoryAndHistoricalNodeCounts() {
        try (Harness harness = new Harness()) {
            harness.identify();
            harness.accept("""
                    {"timestamp":"2026-07-30T11:00:00Z",
                     "event":"FSDJump","StarSystem":"Episode A",
                     "SystemAddress":2001}
                    """);
            harness.accept("""
                    {"timestamp":"2026-07-30T11:00:01Z",
                     "event":"Touchdown","StarSystem":"Episode A",
                     "SystemAddress":2001,"Body":"A 1","BodyID":1}
                    """);
            harness.accept("""
                    {"timestamp":"2026-07-30T11:00:02Z",
                     "event":"Disembark","StarSystem":"Episode A",
                     "SystemAddress":2001,"Body":"A 1","BodyID":1}
                    """);
            harness.accept("""
                    {"timestamp":"2026-07-30T11:00:03Z",
                     "event":"Touchdown","StarSystem":"Episode A",
                     "SystemAddress":2001,"Body":"A 1","BodyID":1}
                    """);

            ProjectedObservation newEpisode = harness.accept("""
                    {"timestamp":"2026-07-30T11:01:00Z",
                     "event":"FSDJump","StarSystem":"Episode B",
                     "SystemAddress":2002}
                    """);
            ActiveEpisodeSituation rootOnly =
                    active(newEpisode.behaviorSituation());
            assertEquals(1, rootOnly.totalOccurrenceCount());
            assertEquals(
                    Map.of(NormalizedEventType.SYSTEM_ENTRY, 1L),
                    rootOnly.occurrenceCounts()
            );
            assertEquals(
                    NormalizedEventType.SYSTEM_ENTRY,
                    rootOnly.cursor().eventType()
            );

            ProjectedObservation currentTouchdown = harness.accept("""
                    {"timestamp":"2026-07-30T11:01:01Z",
                     "event":"Touchdown","StarSystem":"Episode B",
                     "SystemAddress":2002,"Body":"B 1","BodyID":1}
                    """);
            ActiveEpisodeSituation active =
                    active(currentTouchdown.behaviorSituation());
            assertEquals(
                    Map.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            1L,
                            NormalizedEventType.TOUCHDOWN,
                            1L
                    ),
                    active.occurrenceCounts()
            );
            assertEquals(
                    active.trajectory().size(),
                    active.occurrenceCounts().values().stream()
                            .mapToLong(Long::longValue)
                            .sum()
            );
            assertEquals(
                    active.cursor().occurrenceId(),
                    active.currentOccurrence().occurrenceId()
            );
            assertEquals(
                    active.trajectory().getLast(),
                    active.currentOccurrence()
            );

            long historicalTouchdowns = harness.service
                    .graph(GRAPH_ID)
                    .orElseThrow()
                    .nodes()
                    .stream()
                    .filter(node -> node.eventType().equals(
                            NormalizedEventType.TOUCHDOWN
                    ))
                    .mapToLong(EventTypeNode::rawOccurrenceCount)
                    .findFirst()
                    .orElseThrow();
            assertEquals(3, historicalTouchdowns);
            assertEquals(
                    1L,
                    active.occurrenceCounts().get(
                            NormalizedEventType.TOUCHDOWN
                    )
            );
            assertEquals(2, harness.service.episodes(GRAPH_ID).size());
            assertTrue(harness.service.episodes(GRAPH_ID).stream()
                    .filter(episode -> !episode.active())
                    .allMatch(episode -> episode.completionReason()
                            == EpisodeCompletionReason.NEXT_SYSTEM));
        }
    }

    @Test
    void predictionsReuseCalculatorAndFriendsKeepsTrajectoryUnchanged() {
        try (Harness harness = new Harness()) {
            String time = "2026-07-30T12:00:00Z";
            harness.identifyAt(time);
            harness.accept(jump(time, "Branch A", 3001));
            harness.accept(fssScan(time));
            harness.accept("""
                    {"timestamp":"%s","event":"Touchdown",
                     "StarSystem":"Branch A","SystemAddress":3001,
                     "Body":"A 1","BodyID":1}
                    """.formatted(time));

            harness.accept(jump(time, "Branch B", 3002));
            harness.accept(fssScan(time));
            harness.accept("""
                    {"timestamp":"%s","event":"Disembark",
                     "StarSystem":"Branch B","SystemAddress":3002,
                     "Body":"B 1","BodyID":1}
                    """.formatted(time));

            harness.accept(jump(time, "Branch C", 3003));
            ProjectedObservation current = harness.accept(fssScan(time));
            ActiveEpisodeSituation currentEpisode =
                    active(current.behaviorSituation());
            List<SituationNextEventPrediction> predictions =
                    current.behaviorSituation().likelyNext();

            assertEquals(
                    List.of(
                            NormalizedEventType.DISEMBARK,
                            NormalizedEventType.TOUCHDOWN
                    ),
                    predictions.stream()
                            .map(SituationNextEventPrediction
                                    ::predictedEventType)
                            .toList()
            );
            assertTrue(predictions.stream().allMatch(prediction ->
                    prediction.sourceEventType().equals(
                            NormalizedEventType.FSS_DISCOVERY_SCAN
                    )));
            assertEquals(
                    1.0,
                    predictions.stream()
                            .mapToDouble(
                                    SituationNextEventPrediction::probability
                            )
                            .sum(),
                    1.0e-12
            );
            assertEquals(
                    predictions.get(0).probability(),
                    predictions.get(1).probability(),
                    1.0e-12
            );

            List<NextEventPrediction> direct =
                    harness.query.predictNext(
                            GRAPH_ID,
                            new BehaviorContextAdapter().toContextSnapshot(
                                    current.currentState()
                            ),
                            Instant.parse(time),
                            100
                    );
            assertEquals(
                    direct.stream()
                            .map(NextEventPrediction::predictedEventType)
                            .toList(),
                    predictions.stream()
                            .map(SituationNextEventPrediction
                                    ::predictedEventType)
                            .toList()
            );
            assertEquals(
                    direct.stream()
                            .map(NextEventPrediction::probability)
                            .toList(),
                    predictions.stream()
                            .map(SituationNextEventPrediction::probability)
                            .toList()
            );

            ProjectedObservation friends = harness.accept("""
                    {"timestamp":"2026-07-30T12:00:00Z",
                     "event":"Friends","Status":"Online","Name":"Wingmate"}
                    """);
            assertEquals(
                    BehaviorGraphApplyStatus.NOT_APPLICABLE,
                    friends.graphResult().status()
            );
            assertEquals(
                    BehaviorSituationCaptureStatus.UNCHANGED,
                    friends.behaviorSituation().captureStatus()
            );
            assertEquals(current.currentState(), friends.currentState());
            assertEquals(
                    currentEpisode.graphVersion(),
                    active(friends.behaviorSituation()).graphVersion()
            );
            assertEquals(
                    currentEpisode.topologyVersion(),
                    active(friends.behaviorSituation()).topologyVersion()
            );
            assertEquals(
                    currentEpisode.trajectory(),
                    active(friends.behaviorSituation()).trajectory()
            );
            assertEquals(
                    current.busSequence() + 1,
                    friends.busSequence()
            );

            List<SituationNextEventPrediction> frozenPredictions =
                    current.behaviorSituation().likelyNext();
            harness.accept("""
                    {"timestamp":"2026-07-30T12:00:00Z",
                     "event":"Touchdown","StarSystem":"Branch C",
                     "SystemAddress":3003,"Body":"C 1","BodyID":1}
                    """);
            assertEquals(
                    List.of(0.5, 0.5),
                    frozenPredictions.stream()
                            .map(SituationNextEventPrediction::probability)
                            .toList()
            );
        }
    }

    @Test
    void captureRejectsRevisionThatDoesNotMatchCommittedGraph() {
        try (Harness harness = new Harness()) {
            harness.identify();
            ProjectedObservation jump = harness.accept("""
                    {"timestamp":"2026-07-30T13:00:00Z",
                     "event":"FSDJump","StarSystem":"Mismatch",
                     "SystemAddress":4001}
                    """);
            BehaviorGraphApplyResult actual = jump.graphResult();
            BehaviorGraphApplyResult mismatched =
                    new BehaviorGraphApplyResult(
                            actual.busSequence(),
                            actual.status(),
                            actual.changes(),
                            actual.activeGraphId(),
                            actual.activeEpisodeId(),
                            actual.cursor(),
                            java.util.OptionalLong.of(
                                    actual.graphVersion().orElseThrow() + 1
                            ),
                            actual.topologyVersion()
                    );

            assertThrows(
                    BehaviorSituationInconsistencyException.class,
                    () -> harness.query.capture(
                            jump.trigger(),
                            jump.currentState(),
                            mismatched
                    )
            );
        }
    }

    private static ActiveEpisodeSituation active(
            BehaviorSituationSnapshot snapshot
    ) {
        assertTrue(
                snapshot.captureStatus()
                        == BehaviorSituationCaptureStatus.AVAILABLE
                        || snapshot.captureStatus()
                        == BehaviorSituationCaptureStatus.UNCHANGED
        );
        return snapshot.activeEpisode().orElseThrow();
    }

    private static List<NormalizedEventType> eventTypes(
            ActiveEpisodeSituation episode
    ) {
        return episode.trajectory().stream()
                .map(occurrence -> occurrence.eventType())
                .toList();
    }

    private static String jump(
            String timestamp,
            String systemName,
            long systemAddress
    ) {
        return """
                {"timestamp":"%s","event":"FSDJump",
                 "StarSystem":"%s","SystemAddress":%d}
                """.formatted(timestamp, systemName, systemAddress);
    }

    private static String fssScan(String timestamp) {
        return """
                {"timestamp":"%s","event":"FSSDiscoveryScan",
                 "Progress":1.0,"BodyCount":4,"NonBodyCount":0}
                """.formatted(timestamp);
    }

    private static final class Harness implements AutoCloseable {

        private final BehaviorGraphService service;
        private final BehaviorGraphQueryService query;
        private final ObservationProjectionCoordinator coordinator;
        private final JournalFixture journal = new JournalFixture();
        private final List<ProjectedObservation> projected =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        private Harness() {
            service = new BehaviorGraphService(
                    new BehaviorGraphConfiguration(
                            true,
                            Path.of("target", "situation-test-unused"),
                            Duration.ofDays(30),
                            2.0,
                            50,
                            false
                    ),
                    new InMemoryBehaviorGraphStore()
            );
            query = new BehaviorGraphQueryService(service);
            ProjectedObservationBus downstream =
                    new ProjectedObservationBus();
            downstream.subscribe("collector", projected::add);
            coordinator = new ObservationProjectionCoordinator(
                    new CurrentGameStateProjector(),
                    Optional.of(
                            new BehaviorGraphObservationProcessor(service)
                    ),
                    Optional.of(query),
                    downstream
            );
        }

        private void identify() {
            identifyAt("2026-07-30T09:59:59Z");
        }

        private void identifyAt(String timestamp) {
            accept("""
                    {"timestamp":"%s","event":"LoadGame","FID":"F500",
                     "ShipID":42,"Ship":"krait_mkii"}
                    """.formatted(timestamp));
        }

        private ProjectedObservation accept(String rawJson) {
            PublishedObservation<JournalEventObservation> observation =
                    journal.observation(rawJson);
            coordinator.submit(observation)
                    .toCompletableFuture()
                    .join();
            return projected.getLast();
        }

        @Override
        public void close() {
            coordinator.shutdown().toCompletableFuture().join();
        }
    }

    private static final class JournalFixture {

        private static final ObservationSource SOURCE =
                new ObservationSource(
                        "behavior-situation-test",
                        "journal"
                );
        private static final String BASENAME =
                "Journal.behavior-situation-test.log";

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
            ParsedJournalRecord parsed =
                    (ParsedJournalRecord) parser.parse(
                            new CompleteJournalRecord(
                                    BASENAME,
                                    sourceOffset,
                                    bytes
                            )
                    );
            sourceOffset += bytes.length + 1L;
            ObservationDraft<JournalEventObservation> draft =
                    adapter.adapt(
                            parsed,
                            ObservationCaptureMode.REPLAY,
                            parsed.optionalJournalTimestamp()
                                    .orElse(Instant.EPOCH)
                    );
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
