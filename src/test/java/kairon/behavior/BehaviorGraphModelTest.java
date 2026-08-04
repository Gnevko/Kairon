package kairon.behavior;

import kairon.behavior.graph.BehaviorGraphIds;
import kairon.behavior.export.BehaviorGraphExporter;
import kairon.behavior.model.ContextKey;
import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.EdgeKey;
import kairon.behavior.model.EpisodeEntrySource;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.EventOccurrenceSource;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.OccurrenceTransition;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.model.TransitionOccurrenceId;
import kairon.behavior.model.WeightedCounter;
import kairon.behavior.normalize.NormalizedEventType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BehaviorGraphModelTest {

    private static final Duration HALF_LIFE = Duration.ofDays(30);
    private static final Instant START =
            Instant.parse("2026-01-01T00:00:00Z");
    private static final GraphId GRAPH_ID = new GraphId("F12345678", 9);
    private static final SystemEpisodeId EPISODE_ID =
            new SystemEpisodeId("episode-1");
    private static final ContextSnapshot CONTEXT = context(
            null,
            null,
            null,
            null
    );

    @Test
    void repeatedTypeUsesOneNodeWhileEpisodeKeepsOrderedOccurrences() {
        EventOccurrence root = occurrence(
                "root",
                NormalizedEventType.SYSTEM_ENTRY,
                START,
                0
        );
        EventOccurrence laterSameTimestamp = occurrence(
                "touchdown-2",
                NormalizedEventType.TOUCHDOWN,
                START.plusSeconds(1),
                2
        );
        EventOccurrence earlierSameTimestamp = occurrence(
                "touchdown-1",
                NormalizedEventType.TOUCHDOWN,
                START.plusSeconds(1),
                1
        );

        SystemEpisode episode = SystemEpisode.startWithRoot(
                        EPISODE_ID,
                        GRAPH_ID,
                        10477373803L,
                        "Test System",
                        EpisodeEntrySource.FSD_JUMP,
                        root
                )
                .appendOccurrence(
                        earlierSameTimestamp,
                        transition(
                                "root-to-touchdown-1",
                                root.id().value(),
                                earlierSameTimestamp.id().value(),
                                root.eventType(),
                                earlierSameTimestamp.eventType(),
                                earlierSameTimestamp.timestamp(),
                                ContextKey.EMPTY
                        )
                )
                .appendOccurrence(
                        laterSameTimestamp,
                        transition(
                                "touchdown-1-to-touchdown-2",
                                earlierSameTimestamp.id().value(),
                                laterSameTimestamp.id().value(),
                                earlierSameTimestamp.eventType(),
                                laterSameTimestamp.eventType(),
                                laterSameTimestamp.timestamp(),
                                ContextKey.EMPTY
                        )
                );
        ShipBehaviorGraph graph = ShipBehaviorGraph.empty(
                        GRAPH_ID,
                        "diamondback_explorer",
                        "Kairon",
                        "loadout-a"
                )
                .recordOccurrence(laterSameTimestamp)
                .recordOccurrence(earlierSameTimestamp);

        assertEquals(1, graph.nodes().size());
        assertEquals(
                NormalizedEventType.TOUCHDOWN,
                graph.nodes().getFirst().eventType()
        );
        assertEquals(2, graph.nodes().getFirst().rawOccurrenceCount());
        assertEquals(
                List.of(
                        root.id(),
                        earlierSameTimestamp.id(),
                        laterSameTimestamp.id()
                ),
                episode.timeline().stream()
                        .map(EventOccurrence::id)
                        .toList()
        );
        assertEquals(
                List.of(earlierSameTimestamp.id(), laterSameTimestamp.id()),
                episode.occurrencesByEventType()
                        .get(NormalizedEventType.TOUCHDOWN)
        );
        assertNotSame(
                episode.timeline(),
                episode.occurrencesByEventType()
                        .get(NormalizedEventType.TOUCHDOWN)
        );
    }

    @Test
    void occurrenceTransitionsAggregateRepeatedDirectedSelfAndCycleEdges() {
        ShipBehaviorGraph graph = ShipBehaviorGraph.empty(
                GRAPH_ID,
                "diamondback_explorer",
                "Kairon",
                "loadout-a"
        );
        graph = graph
                .recordTransition(transition(
                        "t1",
                        "a1",
                        "b1",
                        NormalizedEventType.TOUCHDOWN,
                        NormalizedEventType.DISEMBARK,
                        START.plusSeconds(1),
                        ContextKey.EMPTY
                ), HALF_LIFE)
                .recordTransition(transition(
                        "t2",
                        "a2",
                        "b2",
                        NormalizedEventType.TOUCHDOWN,
                        NormalizedEventType.DISEMBARK,
                        START.plusSeconds(2),
                        ContextKey.EMPTY
                ), HALF_LIFE)
                .recordTransition(transition(
                        "t3",
                        "a3",
                        "a4",
                        NormalizedEventType.TOUCHDOWN,
                        NormalizedEventType.TOUCHDOWN,
                        START.plusSeconds(3),
                        ContextKey.EMPTY
                ), HALF_LIFE)
                .recordTransition(transition(
                        "t4",
                        "b3",
                        "a5",
                        NormalizedEventType.DISEMBARK,
                        NormalizedEventType.TOUCHDOWN,
                        START.plusSeconds(4),
                        ContextKey.EMPTY
                ), HALF_LIFE);

        assertEquals(3, graph.edges().size());
        assertEquals(
                2,
                graph.edge(new EdgeKey(
                        NormalizedEventType.TOUCHDOWN,
                        NormalizedEventType.DISEMBARK
                )).globalCounter().rawCount()
        );
        assertNotNull(graph.edge(new EdgeKey(
                NormalizedEventType.TOUCHDOWN,
                NormalizedEventType.TOUCHDOWN
        )));
        assertNotNull(graph.edge(new EdgeKey(
                NormalizedEventType.DISEMBARK,
                NormalizedEventType.TOUCHDOWN
        )));
    }

    @Test
    void weightedCounterPreservesRawHistoryAndDecaysIncrementally() {
        WeightedCounter counter = WeightedCounter.empty()
                .record(START, HALF_LIFE)
                .record(START.plus(HALF_LIFE), HALF_LIFE);

        assertEquals(2, counter.rawCount());
        assertEquals(1.5, counter.decayedValue(), 1.0e-12);
        assertEquals(
                0.75,
                counter.valueAt(START.plus(HALF_LIFE.multipliedBy(2)),
                        HALF_LIFE),
                1.0e-12
        );
        assertEquals(2, counter.rawCount());
    }

    @Test
    void oldCounterHasLessInfluenceThanFreshCounterWithSameRawCount() {
        Instant evaluationTime = START.plus(Duration.ofDays(60));
        WeightedCounter oldCounter = WeightedCounter.empty()
                .record(START, HALF_LIFE);
        WeightedCounter freshCounter = WeightedCounter.empty()
                .record(START.plus(Duration.ofDays(59)), HALF_LIFE);

        assertEquals(oldCounter.rawCount(), freshCounter.rawCount());
        assertTrue(
                oldCounter.valueAt(evaluationTime, HALF_LIFE)
                        < freshCounter.valueAt(evaluationTime, HALF_LIFE)
        );
    }

    @Test
    void deterministicIdsDependOnlyOnStableInputs() {
        EventOccurrenceId occurrence =
                BehaviorGraphIds.journalOccurrence(
                        GRAPH_ID,
                        "journal-observation-42"
                );
        SystemEpisodeId first = BehaviorGraphIds.episode(
                GRAPH_ID,
                occurrence,
                EpisodeEntrySource.FSD_JUMP
        );
        SystemEpisodeId repeated = BehaviorGraphIds.episode(
                GRAPH_ID,
                occurrence,
                EpisodeEntrySource.FSD_JUMP
        );
        SystemEpisodeId otherShip = BehaviorGraphIds.episode(
                new GraphId("F12345678", 14),
                occurrence,
                EpisodeEntrySource.FSD_JUMP
        );

        assertEquals(first, repeated);
        assertTrue(first.value().startsWith("bge1-"));
        assertTrue(!first.equals(otherShip));
        assertEquals(
                BehaviorGraphIds.transition(
                        first,
                        new EventOccurrenceId("from"),
                        new EventOccurrenceId("to")
                ),
                BehaviorGraphIds.transition(
                        repeated,
                        new EventOccurrenceId("from"),
                        new EventOccurrenceId("to")
                )
        );
    }

    @Test
    void aggregateExportHasStableOrderingIndependentOfConstructionOrder() {
        EventOccurrence touchdown = occurrence(
                "touchdown",
                NormalizedEventType.TOUCHDOWN,
                START.plusSeconds(1),
                1
        );
        EventOccurrence disembark = occurrence(
                "disembark",
                NormalizedEventType.DISEMBARK,
                START.plusSeconds(2),
                2
        );
        OccurrenceTransition toDisembark = transition(
                "to-disembark",
                "touchdown",
                "disembark",
                NormalizedEventType.TOUCHDOWN,
                NormalizedEventType.DISEMBARK,
                START.plusSeconds(2),
                new ContextKey("vehicle=SHIP|bodyHasBiology=true")
        );
        OccurrenceTransition toLiftoff = transition(
                "to-liftoff",
                "touchdown-2",
                "liftoff",
                NormalizedEventType.TOUCHDOWN,
                NormalizedEventType.LIFTOFF,
                START.plusSeconds(3),
                new ContextKey("vehicle=SHIP|bodyHasBiology=false")
        );
        ShipBehaviorGraph forward = ShipBehaviorGraph.empty(
                        GRAPH_ID,
                        "diamondback_explorer",
                        "Kairon",
                        "loadout-a"
                )
                .recordOccurrence(touchdown)
                .recordOccurrence(disembark)
                .recordTransition(toDisembark, HALF_LIFE)
                .recordTransition(toLiftoff, HALF_LIFE);
        ShipBehaviorGraph reverse = ShipBehaviorGraph.empty(
                        GRAPH_ID,
                        "diamondback_explorer",
                        "Kairon",
                        "loadout-a"
                )
                .recordOccurrence(disembark)
                .recordOccurrence(touchdown)
                .recordTransition(toLiftoff, HALF_LIFE)
                .recordTransition(toDisembark, HALF_LIFE);

        BehaviorGraphExporter exporter = new BehaviorGraphExporter();
        Instant evaluationTime = START.plusSeconds(10);
        assertEquals(
                exporter.exportGraph(forward, evaluationTime, HALF_LIFE),
                exporter.exportGraph(reverse, evaluationTime, HALF_LIFE)
        );
    }

    @Test
    void episodeRejectsAMissingAdjacentOccurrenceTransition() {
        EventOccurrence root = occurrence(
                "root-missing-edge",
                NormalizedEventType.SYSTEM_ENTRY,
                START,
                0
        );
        EventOccurrence touchdown = occurrence(
                "touchdown-missing-edge",
                NormalizedEventType.TOUCHDOWN,
                START.plusSeconds(1),
                1
        );

        assertThrows(IllegalArgumentException.class, () ->
                new SystemEpisode(
                        SystemEpisode.SCHEMA_VERSION,
                        EPISODE_ID,
                        GRAPH_ID,
                        10477373803L,
                        "Test System",
                        START,
                        null,
                        EpisodeEntrySource.FSD_JUMP,
                        null,
                        root.id(),
                        List.of(root, touchdown),
                        Map.of(
                                NormalizedEventType.SYSTEM_ENTRY,
                                List.of(root.id()),
                                NormalizedEventType.TOUCHDOWN,
                                List.of(touchdown.id())
                        ),
                        List.of()
                ));
    }

    private static EventOccurrence occurrence(
            String id,
            NormalizedEventType type,
            Instant timestamp,
            long sequence
    ) {
        return new EventOccurrence(
                new EventOccurrenceId(id),
                GRAPH_ID,
                EPISODE_ID,
                sequence,
                type,
                type.value(),
                EventOccurrenceSource.JOURNAL,
                timestamp,
                sequence,
                "Journal.test.log",
                Map.of(),
                CONTEXT
        );
    }

    private static OccurrenceTransition transition(
            String id,
            String fromOccurrence,
            String toOccurrence,
            NormalizedEventType from,
            NormalizedEventType to,
            Instant observedAt,
            ContextKey contextKey
    ) {
        return new OccurrenceTransition(
                new TransitionOccurrenceId(id),
                EPISODE_ID,
                new EventOccurrenceId(fromOccurrence),
                new EventOccurrenceId(toOccurrence),
                from,
                to,
                observedAt,
                contextKey
        );
    }

    static ContextSnapshot context(
            Integer biologicalSignals,
            Boolean landable,
            String vehicle,
            Boolean bodyHasBiology
    ) {
        return new ContextSnapshot(
                "F12345678",
                9L,
                "diamondback_explorer",
                "Kairon",
                "loadout-a",
                10477373803L,
                "Test System",
                3L,
                "Test System 3",
                "Planet",
                null,
                null,
                vehicle,
                biologicalSignals,
                null,
                landable,
                null,
                null,
                null,
                null,
                bodyHasBiology,
                null
        );
    }
}
