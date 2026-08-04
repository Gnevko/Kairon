package kairon.behavior;

import kairon.behavior.event.BehaviorGraphEvent;
import kairon.behavior.event.BehaviorGraphEvent.ActiveGraphChanged;
import kairon.behavior.event.BehaviorGraphEvent.ReplayCompleted;
import kairon.behavior.event.BehaviorGraphEventSource;
import kairon.behavior.graph.BehaviorGraphQueryService;
import kairon.behavior.graph.BehaviorGraphService;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationEdge;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationNode;
import kairon.behavior.model.ContextKey;
import kairon.behavior.model.EdgeKey;
import kairon.behavior.model.EpisodeEntrySource;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.EventOccurrenceSource;
import kairon.behavior.model.EventTypeNode;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.OccurrenceTransition;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.model.SystemEpisodeSummary;
import kairon.behavior.model.TransitionEdge;
import kairon.behavior.model.TransitionOccurrenceId;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.behavior.persistence.InMemoryBehaviorGraphStore;
import kairon.config.KaironConfiguration.BehaviorGraphConfiguration;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.observation.journal.JournalObservationAdapter
        .JournalSourcePosition;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.source.ObservationSourceSignal
        .ObservationSourceSignalType;
import kairon.state.CurrentGameStateProjector;
import kairon.state.CurrentGameStateProjection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BehaviorGraphVisualizationQueryTest {

    private static final GraphId SHIP_9 =
            new GraphId("F12345678", 9);
    private static final GraphId SHIP_14 =
            new GraphId("F12345678", 14);
    private static final Duration HALF_LIFE = Duration.ofDays(30);
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-07-24T10:00:00Z");

    @Test
    void snapshotIsImmutableAndContainsOnlyAggregateVisualizationData() {
        List<VisualizationNode> sourceNodes = new ArrayList<>(List.of(
                new VisualizationNode(
                        NormalizedEventType.SYSTEM_ENTRY,
                        "System Entry",
                        0
                )
        ));
        List<VisualizationEdge> sourceEdges = new ArrayList<>();
        BehaviorGraphVisualizationSnapshot snapshot =
                new BehaviorGraphVisualizationSnapshot(
                        SHIP_9,
                        "Endeavour",
                        3,
                        2,
                        OBSERVED_AT,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        sourceNodes,
                        sourceEdges
                );

        sourceNodes.clear();
        sourceEdges.add(new VisualizationEdge(
                NormalizedEventType.SYSTEM_ENTRY,
                NormalizedEventType.FSS_DISCOVERY_SCAN,
                1,
                1.0
        ));

        assertEquals(1, snapshot.nodes().size());
        assertTrue(snapshot.edges().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.nodes().clear()
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.edges().clear()
        );
    }

    @Test
    void queryProjectsActiveEpisodeCountsCursorNamesAndDecayedEdgeWeights() {
        InMemoryBehaviorGraphStore store =
                new InMemoryBehaviorGraphStore();
        SystemEpisodeId episodeId = new SystemEpisodeId("episode-1");
        EventOccurrence root = occurrence(
                "occurrence-root",
                episodeId,
                0,
                NormalizedEventType.SYSTEM_ENTRY,
                OBSERVED_AT
        );
        EventOccurrence scan = occurrence(
                "occurrence-scan",
                episodeId,
                1,
                NormalizedEventType.FSS_DISCOVERY_SCAN,
                OBSERVED_AT.plusSeconds(1)
        );
        OccurrenceTransition occurrenceTransition =
                new OccurrenceTransition(
                        new TransitionOccurrenceId("transition-1"),
                        episodeId,
                        root.id(),
                        scan.id(),
                        root.eventType(),
                        scan.eventType(),
                        scan.timestamp(),
                        ContextKey.EMPTY
                );
        SystemEpisode activeEpisode = SystemEpisode.startWithRoot(
                episodeId,
                SHIP_9,
                42L,
                "Snapshot",
                EpisodeEntrySource.FSD_JUMP,
                root
        ).appendOccurrence(scan, occurrenceTransition);
        TransitionEdge edge = TransitionEdge.first(
                new EdgeKey(
                        NormalizedEventType.SYSTEM_ENTRY,
                        NormalizedEventType.FSS_DISCOVERY_SCAN
                ),
                ContextKey.EMPTY,
                OBSERVED_AT,
                HALF_LIFE
        ).record(ContextKey.EMPTY, OBSERVED_AT, HALF_LIFE);
        ShipBehaviorGraph graph = new ShipBehaviorGraph(
                ShipBehaviorGraph.SCHEMA_VERSION,
                SHIP_9,
                "explorer_nx",
                "Endeavour",
                null,
                List.of(
                        new EventTypeNode(
                                NormalizedEventType.SYSTEM_ENTRY,
                                4,
                                OBSERVED_AT,
                                OBSERVED_AT
                        ),
                        new EventTypeNode(
                                NormalizedEventType.FSS_DISCOVERY_SCAN,
                                2,
                                OBSERVED_AT,
                                OBSERVED_AT
                        )
                ),
                List.of(edge),
                List.of(SystemEpisodeSummary.from(activeEpisode)),
                new GraphCursor(
                        SHIP_9,
                        episodeId,
                        scan.id(),
                        NormalizedEventType.FSS_DISCOVERY_SCAN,
                        scan.timestamp()
                )
        );
        store.saveEpisode(activeEpisode);
        store.saveGraph(graph);
        BehaviorGraphQueryService query = new BehaviorGraphQueryService(
                new BehaviorGraphService(
                        configuration(),
                        store
                )
        );

        BehaviorGraphVisualizationSnapshot snapshot =
                query.getVisualizationSnapshot(
                        SHIP_9,
                        OBSERVED_AT.plus(HALF_LIFE)
                ).orElseThrow();

        assertEquals("Endeavour", snapshot.shipDisplayName());
        assertEquals(0, snapshot.graphVersion());
        assertEquals(0, snapshot.topologyVersion());
        assertEquals(
                Optional.of(NormalizedEventType.FSS_DISCOVERY_SCAN),
                snapshot.currentEventType()
        );
        assertEquals(
                Optional.of(scan.id()),
                snapshot.currentOccurrenceId()
        );
        assertEquals(Optional.of(episodeId), snapshot.activeEpisodeId());
        assertEquals(
                List.of("FSS Discovery Scan", "System Entry"),
                snapshot.nodes().stream()
                        .map(VisualizationNode::displayName)
                        .toList()
        );
        assertEquals(
                List.of(1L, 1L),
                snapshot.nodes().stream()
                        .map(VisualizationNode::activeEpisodeOccurrenceCount)
                        .toList()
        );
        assertEquals(
                List.of(2L, 4L),
                graph.nodes().stream()
                        .map(EventTypeNode::rawOccurrenceCount)
                        .toList()
        );
        VisualizationEdge projectedEdge = snapshot.edges().getFirst();
        assertEquals(2, projectedEdge.rawCount());
        assertEquals(1.0, projectedEdge.effectiveWeight(), 1.0e-12);
    }

    @Test
    void activeEpisodeOverlayResetsCountsButRetainsGlobalGraphAndEdges() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T10:00:01Z","event":"FSDJump",
                 "StarSystem":"Regression","SystemAddress":3001}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:00:02Z",
                 "event":"FSSDiscoveryScan","SystemAddress":3001,
                 "BodyCount":4,"NonBodyCount":0}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:00:03Z","event":"Touchdown",
                 "StarSystem":"Regression","SystemAddress":3001,
                 "Body":"Regression 2","BodyID":2}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:00:04Z","event":"Disembark",
                 "StarSystem":"Regression","SystemAddress":3001,
                 "Body":"Regression 2","BodyID":2}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:00:05Z","event":"Touchdown",
                 "StarSystem":"Regression","SystemAddress":3001,
                 "Body":"Regression 3","BodyID":3}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:00:06Z","event":"Disembark",
                 "StarSystem":"Regression","SystemAddress":3001,
                 "Body":"Regression 3","BodyID":3}
                """);

        BehaviorGraphVisualizationSnapshot episodeA = harness.snapshot();
        TransitionEdge learnedEdge = harness.graph(SHIP_9).edge(new EdgeKey(
                NormalizedEventType.SYSTEM_ENTRY,
                NormalizedEventType.FSS_DISCOVERY_SCAN
        ));

        harness.accept("""
                {"timestamp":"2026-07-24T10:00:07Z","event":"FSDJump",
                 "StarSystem":"Regression","SystemAddress":3001}
                """);

        BehaviorGraphVisualizationSnapshot newEpisode = harness.snapshot();
        assertTrue(newEpisode.graphVersion() > episodeA.graphVersion());
        assertEquals(
                episodeA.topologyVersion(),
                newEpisode.topologyVersion()
        );
        assertEquals(
                episodeA.nodes().stream()
                        .map(VisualizationNode::eventType)
                        .toList(),
                newEpisode.nodes().stream()
                        .map(VisualizationNode::eventType)
                        .toList()
        );
        assertEquals(
                learnedEdge,
                harness.graph(SHIP_9).edge(new EdgeKey(
                        NormalizedEventType.SYSTEM_ENTRY,
                        NormalizedEventType.FSS_DISCOVERY_SCAN
                ))
        );
        assertEquals(
                1,
                activeCount(
                        newEpisode,
                        NormalizedEventType.SYSTEM_ENTRY
                )
        );
        assertEquals(
                0,
                activeCount(
                        newEpisode,
                        NormalizedEventType.FSS_DISCOVERY_SCAN
                )
        );
        assertEquals(
                0,
                activeCount(newEpisode, NormalizedEventType.TOUCHDOWN)
        );
        assertEquals(
                0,
                activeCount(newEpisode, NormalizedEventType.DISEMBARK)
        );
        assertEquals(
                Optional.of(NormalizedEventType.SYSTEM_ENTRY),
                newEpisode.currentEventType()
        );

        harness.accept("""
                {"timestamp":"2026-07-24T10:00:08Z",
                 "event":"FSSDiscoveryScan","SystemAddress":3001,
                 "BodyCount":4,"NonBodyCount":0}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:00:09Z","event":"Touchdown",
                 "StarSystem":"Regression","SystemAddress":3001,
                 "Body":"Regression 4","BodyID":4}
                """);

        BehaviorGraphVisualizationSnapshot episodeB = harness.snapshot();
        assertEquals(
                1,
                activeCount(episodeB, NormalizedEventType.SYSTEM_ENTRY)
        );
        assertEquals(
                1,
                activeCount(
                        episodeB,
                        NormalizedEventType.FSS_DISCOVERY_SCAN
                )
        );
        assertEquals(
                1,
                activeCount(episodeB, NormalizedEventType.TOUCHDOWN)
        );
        assertEquals(
                0,
                activeCount(episodeB, NormalizedEventType.DISEMBARK)
        );
        assertEquals(
                Optional.of(NormalizedEventType.TOUCHDOWN),
                episodeB.currentEventType()
        );
        assertTrue(episodeB.currentOccurrenceId().isPresent());
        assertEquals(
                episodeB.activeEpisodeId(),
                harness.service.activeEpisode(SHIP_9).map(SystemEpisode::id)
        );

        ShipBehaviorGraph historical = harness.graph(SHIP_9);
        assertEquals(
                2,
                historicalCount(
                        historical,
                        NormalizedEventType.SYSTEM_ENTRY
                )
        );
        assertEquals(
                2,
                historicalCount(
                        historical,
                        NormalizedEventType.FSS_DISCOVERY_SCAN
                )
        );
        assertEquals(
                3,
                historicalCount(historical, NormalizedEventType.TOUCHDOWN)
        );
        assertEquals(
                2,
                historicalCount(historical, NormalizedEventType.DISEMBARK)
        );
        assertEquals(
                2,
                historical.edge(new EdgeKey(
                        NormalizedEventType.SYSTEM_ENTRY,
                        NormalizedEventType.FSS_DISCOVERY_SCAN
                )).globalCounter().rawCount()
        );
        assertEquals(
                2,
                historical.edge(new EdgeKey(
                        NormalizedEventType.FSS_DISCOVERY_SCAN,
                        NormalizedEventType.TOUCHDOWN
                )).globalCounter().rawCount()
        );
        assertTrue(episodeB.edges().stream().noneMatch(edge ->
                edge.from().equals(NormalizedEventType.DISEMBARK)
                        && edge.to().equals(
                                NormalizedEventType.SYSTEM_ENTRY
                        )));
    }

    @Test
    void repeatedOccurrencesInActiveEpisodeUseTheEpisodeIndex() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T10:10:01Z","event":"FSDJump",
                 "StarSystem":"Repeated Active","SystemAddress":3101}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:10:02Z","event":"Touchdown",
                 "StarSystem":"Repeated Active","SystemAddress":3101,
                 "Body":"Repeated Active 1","BodyID":1}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:10:03Z","event":"Disembark",
                 "StarSystem":"Repeated Active","SystemAddress":3101,
                 "Body":"Repeated Active 1","BodyID":1}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:10:04Z","event":"Touchdown",
                 "StarSystem":"Repeated Active","SystemAddress":3101,
                 "Body":"Repeated Active 2","BodyID":2}
                """);

        assertEquals(
                2,
                activeCount(
                        harness.snapshot(),
                        NormalizedEventType.TOUCHDOWN
                )
        );
    }

    @Test
    void restartWithoutActiveEpisodeKeepsTopologyAndShowsZeroCounts() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T10:20:01Z","event":"FSDJump",
                 "StarSystem":"Completed","SystemAddress":3201}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:20:02Z","event":"Touchdown",
                 "StarSystem":"Completed","SystemAddress":3201,
                 "Body":"Completed 1","BodyID":1}
                """);
        harness.completeReplay();

        BehaviorGraphQueryService restartedQuery =
                new BehaviorGraphQueryService(new BehaviorGraphService(
                        configuration(),
                        harness.store
                ));
        BehaviorGraphVisualizationSnapshot restarted =
                restartedQuery.getVisualizationSnapshot(
                        SHIP_9,
                        OBSERVED_AT.plusSeconds(1)
                ).orElseThrow();

        assertFalse(restarted.nodes().isEmpty());
        assertTrue(restarted.nodes().stream().allMatch(node ->
                node.activeEpisodeOccurrenceCount() == 0));
        assertTrue(restarted.activeEpisodeId().isEmpty());
        assertTrue(restarted.currentEventType().isEmpty());
        assertTrue(restarted.currentOccurrenceId().isEmpty());
    }

    @Test
    void restoredActiveEpisodeRestoresCountsAndCursor() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T10:30:01Z","event":"FSDJump",
                 "StarSystem":"Restored","SystemAddress":3301}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:30:02Z","event":"Touchdown",
                 "StarSystem":"Restored","SystemAddress":3301,
                 "Body":"Restored 1","BodyID":1}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:30:03Z","event":"Disembark",
                 "StarSystem":"Restored","SystemAddress":3301,
                 "Body":"Restored 1","BodyID":1}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:30:04Z","event":"Touchdown",
                 "StarSystem":"Restored","SystemAddress":3301,
                 "Body":"Restored 2","BodyID":2}
                """);
        SystemEpisode persistedEpisode =
                harness.service.activeEpisode(SHIP_9).orElseThrow();
        harness.store.saveEpisode(persistedEpisode);
        harness.store.saveGraph(harness.graph(SHIP_9));

        BehaviorGraphQueryService restartedQuery =
                new BehaviorGraphQueryService(new BehaviorGraphService(
                        configuration(),
                        harness.store
                ));
        BehaviorGraphVisualizationSnapshot restored =
                restartedQuery.getVisualizationSnapshot(
                        SHIP_9,
                        OBSERVED_AT.plusSeconds(1)
                ).orElseThrow();

        assertEquals(
                Optional.of(persistedEpisode.id()),
                restored.activeEpisodeId()
        );
        assertEquals(
                2,
                activeCount(restored, NormalizedEventType.TOUCHDOWN)
        );
        assertEquals(
                Optional.of(NormalizedEventType.TOUCHDOWN),
                restored.currentEventType()
        );
        assertEquals(
                Optional.of(persistedEpisode.timeline().getLast().id()),
                restored.currentOccurrenceId()
        );
    }

    @Test
    void switchingShipsUsesOnlyTheNewShipsActiveEpisodeCounts() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T10:40:01Z","event":"FSDJump",
                 "StarSystem":"Ship Switch","SystemAddress":3401}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:40:02Z","event":"Touchdown",
                 "StarSystem":"Ship Switch","SystemAddress":3401,
                 "Body":"Ship Switch 1","BodyID":1}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:40:03Z","event":"Loadout",
                 "ShipID":14,"Ship":"explorer_nx","ShipName":"Second",
                 "Modules":[]}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:40:04Z","event":"Touchdown",
                 "StarSystem":"Ship Switch","SystemAddress":3401,
                 "Body":"Ship Switch 2","BodyID":2}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:40:05Z","event":"Loadout",
                 "ShipID":9,"Ship":"explorer_nx","ShipName":"First",
                 "Modules":[]}
                """);

        BehaviorGraphVisualizationSnapshot switchedBack =
                harness.query.getVisualizationSnapshot(
                        SHIP_9,
                        OBSERVED_AT.plusSeconds(1)
                ).orElseThrow();
        assertEquals(Optional.of(SHIP_9), harness.query.getActiveGraphId());
        assertEquals(
                0,
                activeCount(switchedBack, NormalizedEventType.TOUCHDOWN)
        );
        assertEquals(
                1,
                historicalCount(
                        harness.graph(SHIP_9),
                        NormalizedEventType.TOUCHDOWN
                )
        );
        assertEquals(
                1,
                historicalCount(
                        harness.graph(SHIP_14),
                        NormalizedEventType.TOUCHDOWN
                )
        );
        assertNotEquals(
                harness.graph(SHIP_9).episodes(),
                harness.graph(SHIP_14).episodes()
        );
    }

    @Test
    void versionsAdvanceMonotonicallyAndIgnoreWeightOnlyTopology() {
        Harness harness = new Harness();
        harness.identifyShip9();
        assertVersions(harness, 0, 0);

        harness.accept("""
                {"timestamp":"2026-07-24T10:00:01Z","event":"FSDJump",
                 "StarSystem":"Versioned","SystemAddress":1001}
                """);
        assertVersions(harness, 1, 1);

        harness.accept("""
                {"timestamp":"2026-07-24T10:00:02Z",
                 "event":"FSSDiscoveryScan","SystemAddress":1001,
                 "BodyCount":4,"NonBodyCount":0}
                """);
        assertVersions(harness, 2, 2);

        harness.accept("""
                {"timestamp":"2026-07-24T10:00:03Z","event":"ApproachBody",
                 "StarSystem":"Versioned","SystemAddress":1001,
                 "Body":"Versioned 2","BodyID":2}
                """);
        assertVersions(harness, 3, 3);

        harness.accept("""
                {"timestamp":"2026-07-24T10:00:04Z",
                 "event":"FSSDiscoveryScan","SystemAddress":1001,
                 "BodyCount":4,"NonBodyCount":0}
                """);
        assertVersions(harness, 4, 4);

        harness.accept("""
                {"timestamp":"2026-07-24T10:00:05Z","event":"ApproachBody",
                 "StarSystem":"Versioned","SystemAddress":1001,
                 "Body":"Versioned 2","BodyID":2}
                """);
        assertVersions(harness, 5, 4);

        harness.completeReplay();
        BehaviorGraphVisualizationSnapshot completed = harness.snapshot();
        assertEquals(6, completed.graphVersion());
        assertEquals(4, completed.topologyVersion());
        assertTrue(completed.currentEventType().isEmpty());
        assertTrue(completed.activeEpisodeId().isEmpty());
        assertTrue(completed.nodes().stream().allMatch(node ->
                node.activeEpisodeOccurrenceCount() == 0));
    }

    @Test
    void activeGraphAndReplayEventsUseDisposableSubscription() {
        Harness harness = new Harness();
        List<BehaviorGraphEvent> events = new ArrayList<>();
        BehaviorGraphEventSource.Subscription subscription =
                harness.service.eventSource().subscribe(events::add);

        assertTrue(harness.query.getActiveGraphId().isEmpty());
        harness.identifyShip9();
        assertEquals(Optional.of(SHIP_9), harness.query.getActiveGraphId());
        ActiveGraphChanged activated = events.stream()
                .filter(ActiveGraphChanged.class::isInstance)
                .map(ActiveGraphChanged.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(SHIP_9, activated.graphId());
        assertTrue(activated.previousGraphId().isEmpty());

        harness.accept("""
                {"timestamp":"2026-07-24T10:00:01Z","event":"FSDJump",
                 "StarSystem":"Events","SystemAddress":2001}
                """);
        harness.completeReplay();
        assertInstanceOf(ReplayCompleted.class, events.getLast());

        harness.accept("""
                {"timestamp":"2026-07-24T10:00:02Z","event":"Loadout",
                 "ShipID":14,"Ship":"explorer_nx","ShipName":"Second",
                 "Modules":[]}
                """);
        assertEquals(Optional.of(SHIP_14), harness.query.getActiveGraphId());
        List<ActiveGraphChanged> activeChanges = events.stream()
                .filter(ActiveGraphChanged.class::isInstance)
                .map(ActiveGraphChanged.class::cast)
                .toList();
        assertEquals(2, activeChanges.size());
        assertEquals(SHIP_14, activeChanges.getLast().graphId());
        assertEquals(
                Optional.of(SHIP_9),
                activeChanges.getLast().previousGraphId()
        );

        subscription.close();
        assertFalse(subscription.isActive());
        int eventCountAfterClose = events.size();
        harness.accept("""
                {"timestamp":"2026-07-24T10:00:03Z","event":"Loadout",
                 "ShipID":9,"Ship":"explorer_nx","ShipName":"Endeavour",
                 "Modules":[]}
                """);

        assertEquals(eventCountAfterClose, events.size());
        assertEquals(Optional.of(SHIP_9), harness.query.getActiveGraphId());
        subscription.close();
    }

    private static EventOccurrence occurrence(
            String occurrenceId,
            SystemEpisodeId episodeId,
            long episodeSequence,
            NormalizedEventType eventType,
            Instant timestamp
    ) {
        return new EventOccurrence(
                new EventOccurrenceId(occurrenceId),
                SHIP_9,
                episodeId,
                episodeSequence,
                eventType,
                eventType.value(),
                EventOccurrenceSource.JOURNAL,
                timestamp,
                episodeSequence,
                "Journal.visualization-query-test.log",
                Map.of(),
                BehaviorGraphModelTest.context(null, null, "SHIP", null)
        );
    }

    private static long activeCount(
            BehaviorGraphVisualizationSnapshot snapshot,
            NormalizedEventType eventType
    ) {
        return snapshot.nodes().stream()
                .filter(node -> node.eventType().equals(eventType))
                .findFirst()
                .orElseThrow()
                .activeEpisodeOccurrenceCount();
    }

    private static long historicalCount(
            ShipBehaviorGraph graph,
            NormalizedEventType eventType
    ) {
        return graph.nodes().stream()
                .filter(node -> node.eventType().equals(eventType))
                .findFirst()
                .orElseThrow()
                .rawOccurrenceCount();
    }

    private static void assertVersions(
            Harness harness,
            long graphVersion,
            long topologyVersion
    ) {
        BehaviorGraphVisualizationSnapshot snapshot = harness.snapshot();
        assertEquals(graphVersion, snapshot.graphVersion());
        assertEquals(topologyVersion, snapshot.topologyVersion());
    }

    private static BehaviorGraphConfiguration configuration() {
        return new BehaviorGraphConfiguration(
                true,
                Path.of("target", "behavior-visualization-test-unused"),
                HALF_LIFE,
                2.0,
                50,
                false
        );
    }

    private static final class Harness {

        private static final String BASENAME =
                "Journal.behavior-visualization-test.log";
        private static final ObservationSource SOURCE =
                new ObservationSource(
                        "elite-journal",
                        "behavior-visualization-test"
                );

        private final JournalLineParser parser = new JournalLineParser();
        private final JournalObservationAdapter adapter =
                new JournalObservationAdapter(SOURCE);
        private final InMemoryBehaviorGraphStore store =
                new InMemoryBehaviorGraphStore();
        private final CurrentGameStateProjector currentGameState =
                new CurrentGameStateProjector();
        private final BehaviorGraphService service =
                new BehaviorGraphService(
                        configuration(),
                        store
                );
        private final BehaviorGraphQueryService query =
                new BehaviorGraphQueryService(service);

        private long busSequence;
        private long sourceOffset;

        private void identifyShip9() {
            accept("""
                    {"timestamp":"2026-07-24T10:00:00Z",
                     "event":"LoadGame","FID":"F12345678",
                     "ShipID":9,"Ship":"explorer_nx",
                     "ShipName":"Endeavour"}
                    """);
        }

        private PublishedObservation<JournalEventObservation> accept(
                String rawJson
        ) {
            byte[] bytes = rawJson.strip().getBytes(StandardCharsets.UTF_8);
            ParsedJournalRecord parsed = assertInstanceOf(
                    ParsedJournalRecord.class,
                    parser.parse(new CompleteJournalRecord(
                            BASENAME,
                            sourceOffset,
                            bytes
                    ))
            );
            sourceOffset += bytes.length + 1L;
            ObservationDraft<JournalEventObservation> draft = adapter.adapt(
                    parsed,
                    ObservationCaptureMode.REPLAY,
                    parsed.optionalJournalTimestamp().orElse(Instant.EPOCH)
            );
            PublishedObservation<JournalEventObservation> published =
                    publish(draft, ++busSequence);
            CurrentGameStateProjection projection =
                    currentGameState.applyAndCapture(published);
            service.onObservation(
                    published,
                    projection.currentState(),
                    projection.observationContext()
            );
            return published;
        }

        private void completeReplay() {
            PublishedObservation<ObservationSourceSignal> observation =
                    new PublishedObservation<>(
                            "replay-exhausted-" + (busSequence + 1),
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
            CurrentGameStateProjection projection =
                    currentGameState.applyAndCapture(observation);
            service.completeReplay(
                    observation,
                    projection.currentState()
            );
        }

        private BehaviorGraphVisualizationSnapshot snapshot() {
            return query.getVisualizationSnapshot(
                    SHIP_9,
                    Instant.parse("2026-07-24T10:01:00Z")
            ).orElseThrow();
        }

        private ShipBehaviorGraph graph(GraphId graphId) {
            return service.graph(graphId).orElseThrow();
        }

        private static PublishedObservation<JournalEventObservation> publish(
                ObservationDraft<JournalEventObservation> draft,
                long sequence
        ) {
            return new PublishedObservation<>(
                    draft.observationId(),
                    sequence,
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
