package kairon.behavior;

import kairon.behavior.classify.EventSignificancePolicy;
import kairon.behavior.classify.EventSignificancePolicy.EventSignificance;
import kairon.behavior.context.BehaviorContextAdapter;
import kairon.behavior.context.TransitionContextKeyFactory;
import kairon.behavior.context.BodyDetailLookup;
import kairon.projection.RegistryBodyDetail;
import kairon.state.CurrentGameStateSnapshot;
import kairon.system.CurrentSystemRegistry;
import kairon.system.VisitIdentity;
import kairon.behavior.event.BehaviorGraphEvent;
import kairon.behavior.event.BehaviorGraphEvent.BehaviorGraphUpdated;
import kairon.behavior.event.BehaviorGraphEvent.GraphCursorChanged;
import kairon.behavior.event.BehaviorGraphListener;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.graph.BehaviorGraphIds;
import kairon.behavior.graph.BehaviorGraphQueryService;
import kairon.behavior.graph.BehaviorGraphService;
import kairon.behavior.model.ContextKey;
import kairon.behavior.model.EdgeKey;
import kairon.behavior.model.EpisodeCompletionReason;
import kairon.behavior.model.EpisodeEntrySource;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventTypeNode;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.model.TransitionEdge;
import kairon.behavior.normalize.BehaviorEventNormalizer;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.behavior.persistence.BehaviorGraphStore;
import kairon.behavior.persistence.InMemoryBehaviorGraphStore;
import kairon.behavior.persistence.JsonBehaviorGraphStore;
import kairon.behavior.status.StatusStateDeltaAdapter;
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
import kairon.observation.status.StatusObservationAdapter;
import kairon.observation.status.StatusSnapshotObservation;
import kairon.observation.status.StatusSnapshotParser;
import kairon.observation.status.StatusSnapshotParser.ParsedStatusSnapshot;
import kairon.state.CurrentGameStateProjector;
import kairon.state.CurrentGameStateProjection;
import kairon.state.CurrentGameStateSnapshot;
import kairon.state.FlightMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BehaviorGraphServiceTest {

    private static final GraphId SHIP_9 = new GraphId("F12345678", 9);
    private static final GraphId SHIP_14 = new GraphId("F12345678", 14);

    @Test
    void graphUsesCapturedCanonicalStateForOwnershipAndContext() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T09:00:01Z","event":"FSDJump",
                 "StarSystem":"Canonical","SystemAddress":901,
                 "Body":"Canonical A","BodyID":0}
                """);

        CurrentGameStateSnapshot canonical =
                harness.currentGameState.currentSnapshot();
        assertEquals(901L, canonical.systemAddress());
        assertEquals(
                SHIP_9,
                harness.service.currentGraphId().orElseThrow()
        );
        assertEquals(
                new BehaviorContextAdapter().toContextSnapshot(
                        canonical,
                        harness.lastBodies
                ),
                harness.service.currentContext()
        );
        assertEquals(
                harness.service.currentContext(),
                harness.service.activeEpisode(SHIP_9)
                        .orElseThrow()
                        .timeline()
                        .getFirst()
                        .context()
        );
    }

    @Test
    void replayCompletionAndShutdownDoNotResetCanonicalState() {
        Harness replay = new Harness();
        replay.identifyShip9();
        replay.accept("""
                {"timestamp":"2026-07-24T09:10:01Z","event":"FSDJump",
                 "StarSystem":"Replay State","SystemAddress":911}
                """);
        CurrentGameStateSnapshot beforeReplayCompletion =
                replay.currentGameState.currentSnapshot();

        replay.completeReplay();

        assertEquals(
                beforeReplayCompletion,
                replay.currentGameState.currentSnapshot()
        );

        Harness shutdown = new Harness();
        shutdown.identifyShip9();
        shutdown.accept("""
                {"timestamp":"2026-07-24T09:20:01Z","event":"FSDJump",
                 "StarSystem":"Shutdown State","SystemAddress":921}
                """);
        CurrentGameStateSnapshot beforeShutdown =
                shutdown.currentGameState.currentSnapshot();

        shutdown.accept("""
                {"timestamp":"2026-07-24T09:20:02Z","event":"Shutdown"}
                """);

        assertEquals(
                beforeShutdown,
                shutdown.currentGameState.currentSnapshot()
        );
    }

    @Test
    void loadoutDoesNotSplitGraphWhileNewSameTypeShipIsIndependent() {
        Harness harness = new Harness();
        harness.accept("""
                {"timestamp":"2026-07-24T10:00:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx"}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T10:00:01Z","event":"Loadout",
                 "ShipID":9,"Ship":"explorer_nx","ShipName":"First",
                 "Modules":[{"Slot":"MainEngines","Item":"engine_a"}]}
                """);

        ShipBehaviorGraph sameShip = harness.graph(SHIP_9);
        assertTrue(sameShip.nodes().isEmpty());
        assertTrue(sameShip.edges().isEmpty());
        assertNotNull(sameShip.loadoutHash());

        harness.accept("""
                {"timestamp":"2026-07-24T10:00:02Z","event":"Loadout",
                 "ShipID":14,"Ship":"explorer_nx","ShipName":"Second",
                 "Modules":[{"Slot":"MainEngines","Item":"engine_b"}]}
                """);

        ShipBehaviorGraph firstShip = harness.graph(SHIP_9);
        ShipBehaviorGraph secondShip = harness.graph(SHIP_14);
        assertNotEquals(firstShip.graphId(), secondShip.graphId());
        assertTrue(firstShip.nodes().isEmpty());
        assertTrue(secondShip.nodes().isEmpty());
        assertTrue(firstShip.edges().isEmpty());
        assertTrue(secondShip.edges().isEmpty());
        assertNotEquals(firstShip.loadoutHash(), secondShip.loadoutHash());
    }

    @Test
    void repeatedSystemVisitCreatesOneRootPerEpisodeAndNoCrossEdge() {
        Harness harness = new Harness();
        harness.identifyShip9();
        PublishedObservation<JournalEventObservation> firstJump =
                harness.accept("""
                        {"timestamp":"2026-07-24T11:00:01Z",
                         "event":"FSDJump","StarSystem":"Repeated",
                         "SystemAddress":1001}
                        """);
        harness.accept("""
                {"timestamp":"2026-07-24T11:00:02Z",
                 "event":"FSSDiscoveryScan","SystemAddress":1001,
                 "BodyCount":4,"NonBodyCount":0}
                """);
        PublishedObservation<JournalEventObservation> secondJump =
                harness.accept("""
                        {"timestamp":"2026-07-24T11:00:03Z",
                         "event":"FSDJump","StarSystem":"Repeated",
                         "SystemAddress":1001}
                        """);

        List<SystemEpisode> episodes = harness.service.episodes(SHIP_9);
        assertEquals(2, episodes.size());
        assertNotEquals(episodes.get(0).id(), episodes.get(1).id());
        assertEquals(
                EpisodeCompletionReason.NEXT_SYSTEM,
                episodes.get(0).completionReason()
        );
        assertTrue(episodes.get(1).active());
        assertEquals(2, episodes.get(0).timeline().size());
        assertEquals(1, episodes.get(1).timeline().size());
        assertRootFor(episodes.get(0), firstJump);
        assertRootFor(episodes.get(1), secondJump);

        ShipBehaviorGraph graph = harness.graph(SHIP_9);
        assertEquals(
                2,
                graph.nodes().stream()
                        .filter(node -> node.eventType().equals(
                                NormalizedEventType.SYSTEM_ENTRY))
                        .findFirst()
                        .orElseThrow()
                        .rawOccurrenceCount()
        );
        assertNotNull(graph.edge(new EdgeKey(
                NormalizedEventType.SYSTEM_ENTRY,
                NormalizedEventType.FSS_DISCOVERY_SCAN
        )));
        assertNull(graph.edge(new EdgeKey(
                NormalizedEventType.FSS_DISCOVERY_SCAN,
                NormalizedEventType.SYSTEM_ENTRY
        )));
    }

    @Test
    void locationRestoresEpisodeOnceAndAnotherSystemStartsAnotherVisit() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T12:00:01Z","event":"Location",
                 "StarSystem":"Restore A","SystemAddress":2001}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T12:00:02Z","event":"Location",
                 "StarSystem":"Restore A","SystemAddress":2001}
                """);
        assertEquals(1, harness.service.episodes(SHIP_9).size());

        harness.accept("""
                {"timestamp":"2026-07-24T12:00:03Z","event":"Location",
                 "StarSystem":"Restore B","SystemAddress":2002}
                """);

        List<SystemEpisode> episodes = harness.service.episodes(SHIP_9);
        assertEquals(2, episodes.size());
        assertEquals(
                EpisodeEntrySource.LOCATION_RESTORE,
                episodes.get(0).entrySource()
        );
        assertEquals(
                EpisodeEntrySource.LOCATION_RESTORE,
                episodes.get(1).entrySource()
        );
        assertEquals(
                EpisodeCompletionReason.NEXT_SYSTEM,
                episodes.get(0).completionReason()
        );
        // A restored visit records nothing of its own: the Commander was
        // already here, so there is no arrival to count and no cursor for the
        // next event to have followed.
        assertEquals(List.of(), episodes.get(0).timeline());
        assertNull(episodes.get(0).rootOccurrenceId());
        assertTrue(episodes.get(1).awaitingFirstOccurrence());
        assertTrue(harness.service.cursor(SHIP_9).isEmpty());
    }

    @Test
    void noiseAndContextDoNotCreateOccurrencesButContextReachesSignificantOne() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T13:00:01Z","event":"FSDJump",
                 "StarSystem":"Context","SystemAddress":3001}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T13:00:02Z","event":"Music",
                 "MusicTrack":"Exploration"}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T13:00:03Z","event":"Scan",
                 "SystemAddress":3001,"BodyID":4,"BodyName":"Context 4",
                 "PlanetClass":"Icy body","Landable":true,
                 "DistanceFromArrivalLS":42.5}
                """);
        PublishedObservation<JournalEventObservation> touchdown =
                harness.accept("""
                        {"timestamp":"2026-07-24T13:00:04Z",
                         "event":"Touchdown","StarSystem":"Context",
                         "SystemAddress":3001,"Body":"Context 4","BodyID":4}
                        """);

        SystemEpisode episode =
                harness.service.activeEpisode(SHIP_9).orElseThrow();
        assertEquals(2, episode.timeline().size());
        EventOccurrence occurrence = episode.timeline().getLast();
        assertEquals(NormalizedEventType.TOUCHDOWN, occurrence.eventType());
        assertEquals(Boolean.TRUE, occurrence.context().landable());
        assertEquals(42.5, occurrence.context().distanceFromArrivalLs());
        assertEquals(
                BehaviorGraphIds.journalOccurrence(
                        SHIP_9,
                        touchdown.observationId()
                ),
                occurrence.id()
        );
        assertEquals(
                occurrence.id(),
                harness.service.cursor(SHIP_9).orElseThrow().occurrenceId()
        );
        assertFalse(harness.graph(SHIP_9).nodes().stream().anyMatch(node ->
                node.eventType().value().equals("MUSIC")
                        || node.eventType().value().equals("SCAN")));
    }

    @Test
    void repeatedObservationIdentityDoesNotDuplicateOccurrenceOrTransition() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T14:00:01Z","event":"FSDJump",
                 "StarSystem":"Duplicate","SystemAddress":4001}
                """);
        PublishedObservation<JournalEventObservation> touchdown =
                harness.accept("""
                        {"timestamp":"2026-07-24T14:00:02Z",
                         "event":"Touchdown","StarSystem":"Duplicate",
                         "SystemAddress":4001,"Body":"Duplicate 1","BodyID":1}
                        """);
        harness.acceptDuplicate(touchdown);

        SystemEpisode episode =
                harness.service.activeEpisode(SHIP_9).orElseThrow();
        assertEquals(2, episode.timeline().size());
        assertEquals(1, episode.occurrenceTransitions().size());
        assertEquals(
                1,
                harness.graph(SHIP_9).edge(new EdgeKey(
                        NormalizedEventType.SYSTEM_ENTRY,
                        NormalizedEventType.TOUCHDOWN
                )).globalCounter().rawCount()
        );
    }

    @Test
    void repeatedReplayProjectsStoredEpisodeWithoutFreezingOrRelearning() {
        String root = """
                {"timestamp":"2026-07-24T14:10:01Z","event":"FSDJump",
                 "StarSystem":"Replay Projection","SystemAddress":4011}
                """;
        String touchdown = """
                {"timestamp":"2026-07-24T14:10:02Z",
                 "event":"Touchdown","StarSystem":"Replay Projection",
                 "SystemAddress":4011,"Body":"Replay Projection 1",
                 "BodyID":1}
                """;

        Harness learned = new Harness();
        learned.identifyShip9();
        learned.accept(root);
        learned.accept(touchdown);
        learned.completeReplay();

        ShipBehaviorGraph historical = learned.graph(SHIP_9);
        long historicalSystemEntries = historical.nodes().stream()
                .filter(node -> node.eventType().equals(
                        NormalizedEventType.SYSTEM_ENTRY))
                .findFirst()
                .orElseThrow()
                .rawOccurrenceCount();
        long historicalTouchdowns = historical.nodes().stream()
                .filter(node -> node.eventType().equals(
                        NormalizedEventType.TOUCHDOWN))
                .findFirst()
                .orElseThrow()
                .rawOccurrenceCount();
        TransitionEdge historicalEdge = historical.edge(new EdgeKey(
                NormalizedEventType.SYSTEM_ENTRY,
                NormalizedEventType.TOUCHDOWN
        ));

        Harness replay = new Harness(learned.store);
        List<BehaviorGraphEvent> replayEvents = new ArrayList<>();
        replay.service.eventSource().subscribe(replayEvents::add);
        replay.identifyShip9();
        replayEvents.clear();
        replay.accept(root);

        SystemEpisode rootOnly =
                replay.service.activeEpisode(SHIP_9).orElseThrow();
        assertEquals(1, rootOnly.timeline().size());
        assertEquals(
                NormalizedEventType.SYSTEM_ENTRY,
                replay.service.cursor(SHIP_9).orElseThrow().eventType()
        );
        var rootSnapshot = new BehaviorGraphQueryService(replay.service)
                .getVisualizationSnapshot(SHIP_9, Instant.parse(
                        "2026-07-24T14:10:01Z"
                ))
                .orElseThrow();
        assertEquals(
                1,
                rootSnapshot.nodes().stream()
                        .filter(node -> node.eventType().equals(
                                NormalizedEventType.SYSTEM_ENTRY))
                        .findFirst()
                        .orElseThrow()
                        .activeEpisodeOccurrenceCount()
        );
        assertEquals(
                0,
                rootSnapshot.nodes().stream()
                        .filter(node -> node.eventType().equals(
                                NormalizedEventType.TOUCHDOWN))
                        .findFirst()
                        .orElseThrow()
                        .activeEpisodeOccurrenceCount()
        );

        replay.accept(touchdown);

        SystemEpisode advanced =
                replay.service.activeEpisode(SHIP_9).orElseThrow();
        assertEquals(2, advanced.timeline().size());
        assertEquals(
                NormalizedEventType.TOUCHDOWN,
                replay.service.cursor(SHIP_9).orElseThrow().eventType()
        );
        var advancedSnapshot = new BehaviorGraphQueryService(replay.service)
                .getVisualizationSnapshot(SHIP_9, Instant.parse(
                        "2026-07-24T14:10:02Z"
                ))
                .orElseThrow();
        assertTrue(
                advancedSnapshot.graphVersion()
                        > rootSnapshot.graphVersion()
        );
        assertEquals(
                1,
                advancedSnapshot.nodes().stream()
                        .filter(node -> node.eventType().equals(
                                NormalizedEventType.TOUCHDOWN))
                        .findFirst()
                        .orElseThrow()
                        .activeEpisodeOccurrenceCount()
        );
        assertEquals(
                rootSnapshot.topologyVersion(),
                advancedSnapshot.topologyVersion()
        );

        ShipBehaviorGraph afterReplay = replay.graph(SHIP_9);
        assertEquals(
                historicalSystemEntries,
                afterReplay.nodes().stream()
                        .filter(node -> node.eventType().equals(
                                NormalizedEventType.SYSTEM_ENTRY))
                        .findFirst()
                        .orElseThrow()
                        .rawOccurrenceCount()
        );
        assertEquals(
                historicalTouchdowns,
                afterReplay.nodes().stream()
                        .filter(node -> node.eventType().equals(
                                NormalizedEventType.TOUCHDOWN))
                        .findFirst()
                        .orElseThrow()
                        .rawOccurrenceCount()
        );
        assertEquals(
                historicalEdge,
                afterReplay.edge(new EdgeKey(
                        NormalizedEventType.SYSTEM_ENTRY,
                        NormalizedEventType.TOUCHDOWN
                ))
        );
        assertEquals(
                2,
                replayEvents.stream()
                        .filter(GraphCursorChanged.class::isInstance)
                        .count()
        );
        assertEquals(
                2,
                replayEvents.stream()
                        .filter(BehaviorGraphUpdated.class::isInstance)
                        .count()
        );
    }

    @Test
    void repeatedReplayKeepsProjectedRunSuppressionDeterministic() {
        String root = """
                {"timestamp":"2026-07-24T14:20:01Z","event":"FSDJump",
                 "StarSystem":"Replay Suppression","SystemAddress":4021}
                """;
        String firstScoop = """
                {"timestamp":"2026-07-24T14:20:02Z",
                 "event":"FuelScoop","Scooped":5.0,"Total":15.5}
                """;
        String repeatedScoop = """
                {"timestamp":"2026-07-24T14:20:03Z",
                 "event":"FuelScoop","Scooped":0.5,"Total":16.0}
                """;

        Harness learned = new Harness();
        learned.identifyShip9();
        learned.accept(root);
        learned.accept(firstScoop);
        learned.accept(repeatedScoop);
        assertEquals(
                2,
                learned.service.activeEpisode(SHIP_9)
                        .orElseThrow()
                        .timeline()
                        .size()
        );
        learned.completeReplay();

        Harness replay = new Harness(learned.store);
        replay.identifyShip9();
        replay.accept(root);
        replay.accept(firstScoop);
        replay.accept(repeatedScoop);

        SystemEpisode projected =
                replay.service.activeEpisode(SHIP_9).orElseThrow();
        assertEquals(2, projected.timeline().size());
        assertEquals(
                NormalizedEventType.FUEL_SCOOPING,
                replay.service.cursor(SHIP_9).orElseThrow().eventType()
        );
        assertEquals(
                1,
                replay.graph(SHIP_9).nodes().stream()
                        .filter(node -> node.eventType().equals(
                                NormalizedEventType.FUEL_SCOOPING))
                        .findFirst()
                        .orElseThrow()
                        .rawOccurrenceCount()
        );
    }

    @Test
    void shipSwitchFixtureClosesOldEpisodeAndStartsSyntheticNewRoot()
            throws IOException {
        Harness harness = new Harness();
        harness.acceptFixture("ship-switch.jsonl");

        SystemEpisode oldEpisode =
                harness.service.episodes(SHIP_9).getFirst();
        SystemEpisode newEpisode =
                harness.service.activeEpisode(SHIP_14).orElseThrow();
        assertEquals(
                EpisodeCompletionReason.SHIP_SWITCH,
                oldEpisode.completionReason()
        );
        assertEquals(EpisodeEntrySource.SHIP_SWITCH, newEpisode.entrySource());
        assertEquals(
                NormalizedEventType.SYSTEM_ENTRY,
                newEpisode.timeline().getFirst().eventType()
        );
        assertEquals(
                "ShipSwitch",
                newEpisode.timeline().getFirst().originalEventName()
        );
        assertEquals(
                NormalizedEventType.UNDOCKED,
                newEpisode.timeline().getLast().eventType()
        );
        assertNull(harness.graph(SHIP_9).cursor());
        assertEquals(
                newEpisode.timeline().getLast().id(),
                harness.graph(SHIP_14).cursor().occurrenceId()
        );
        BehaviorGraphQueryService query =
                new BehaviorGraphQueryService(harness.service);
        assertEquals(
                2,
                query.listEpisodes(SHIP_14)
                        .getFirst()
                        .occurrenceCount()
        );
        assertEquals(
                List.of(newEpisode.timeline().getLast()),
                query.getOccurrences(
                        SHIP_14,
                        newEpisode.id(),
                        NormalizedEventType.UNDOCKED
                )
        );
        assertNull(harness.graph(SHIP_9).edge(new EdgeKey(
                NormalizedEventType.FSS_DISCOVERY_SCAN,
                NormalizedEventType.SYSTEM_ENTRY
        )));
    }

    @Test
    void shutdownClosesAndPersistsTheActiveEpisode() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T15:00:01Z","event":"FSDJump",
                 "StarSystem":"Shutdown","SystemAddress":5001}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T15:00:02Z",
                 "event":"FSSDiscoveryScan","SystemAddress":5001,
                 "BodyCount":4,"NonBodyCount":0}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T15:00:03Z","event":"Shutdown"}
                """);

        SystemEpisode episode = harness.service.episodes(SHIP_9).getFirst();
        assertFalse(episode.active());
        assertEquals(
                EpisodeCompletionReason.SHUTDOWN,
                episode.completionReason()
        );
        assertNull(harness.graph(SHIP_9).cursor());
        assertTrue(harness.service.activeEpisode(SHIP_9).isEmpty());
        assertEquals(
                episode,
                harness.store.loadEpisode(episode.id()).orElseThrow()
        );
    }

    @Test
    void exobiologyFixtureProducesExactNormalizedPathAndCompactContext()
            throws IOException {
        Harness harness = new Harness();
        harness.acceptFixture("exobiology.jsonl");

        SystemEpisode episode =
                harness.service.activeEpisode(SHIP_9).orElseThrow();
        assertEquals(
                List.of(
                        NormalizedEventType.SYSTEM_ENTRY,
                        NormalizedEventType.FSS_DISCOVERY_SCAN,
                        // Two bodies, two readings; nothing is deduplicated
                        // across bodies.
                        NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                        NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                        NormalizedEventType.FSS_ALL_BODIES_FOUND,
                        NormalizedEventType.SAA_SCAN_COMPLETE,
                        NormalizedEventType.SAA_SIGNALS_FOUND,
                        NormalizedEventType.APPROACH_BODY,
                        NormalizedEventType.SUPERCRUISE_EXIT,
                        NormalizedEventType.AUXILIARY_VEHICLE_LAUNCHED,
                        NormalizedEventType.TOUCHDOWN,
                        NormalizedEventType.DISEMBARK,
                        NormalizedEventType.SCAN_ORGANIC_LOG,
                        NormalizedEventType.EMBARK,
                        NormalizedEventType.LIFTOFF,
                        NormalizedEventType.TOUCHDOWN,
                        NormalizedEventType.DISEMBARK,
                        NormalizedEventType.SCAN_ORGANIC_SAMPLE
                ),
                episode.timeline().stream()
                        .map(EventOccurrence::eventType)
                        .toList()
        );
        EventOccurrence firstTouchdown = episode.timeline().stream()
                .filter(occurrence -> occurrence.eventType().equals(
                        NormalizedEventType.TOUCHDOWN))
                .findFirst()
                .orElseThrow();
        assertEquals(
                5,
                firstTouchdown.context().biologicalSignalCount()
        );
        assertEquals(Boolean.TRUE, firstTouchdown.context().bodyHasBiology());
        assertEquals(17, episode.occurrenceTransitions().size());
        ShipBehaviorGraph graph = harness.graph(SHIP_9);

        // Completing a survey is a deliberate action and has its own node; the
        // signals record that follows is a separate one, and the edge between
        // them is the sequence the Commander actually performed.
        assertTrue(hasNode(graph, NormalizedEventType.SAA_SCAN_COMPLETE));
        assertTrue(hasNode(graph, NormalizedEventType.SAA_SIGNALS_FOUND));
        assertNotNull(graph.edge(new EdgeKey(
                NormalizedEventType.FSS_ALL_BODIES_FOUND,
                NormalizedEventType.SAA_SCAN_COMPLETE
        )));
        assertNotNull(graph.edge(new EdgeKey(
                NormalizedEventType.SAA_SCAN_COMPLETE,
                NormalizedEventType.SAA_SIGNALS_FOUND
        )));
        assertNull(
                graph.edge(new EdgeKey(
                        NormalizedEventType.FSS_ALL_BODIES_FOUND,
                        NormalizedEventType.SAA_SIGNALS_FOUND
                )),
                "the survey no longer follows the system scan directly"
        );

        // What the system scanner reported about a body is a result, and this
        // fixture reports one for each of two bodies. Nothing is merged across
        // bodies, so the run really does hold two of them in a row.
        assertTrue(hasNode(
                graph,
                NormalizedEventType.FSS_BODY_SIGNALS_FOUND
        ));
        assertEquals(
                2L,
                graph.nodes().stream()
                        .filter(node -> node.eventType().equals(
                                NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                        ))
                        .findFirst()
                        .orElseThrow()
                        .rawOccurrenceCount()
        );
        assertNotNull(graph.edge(new EdgeKey(
                NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                NormalizedEventType.FSS_BODY_SIGNALS_FOUND
        )));
    }

    /** The completed survey counts and weighs like any other occurrence. */
    @Test
    void aCompletedSurveyUsesTheOrdinaryOccurrencePath() throws IOException {
        Harness harness = new Harness();
        harness.acceptFixture("exobiology.jsonl");

        ShipBehaviorGraph graph = harness.graph(SHIP_9);
        EventTypeNode node = graph.nodes().stream()
                .filter(candidate -> candidate.eventType()
                        .equals(NormalizedEventType.SAA_SCAN_COMPLETE))
                .findFirst()
                .orElseThrow();
        assertEquals(1L, node.rawOccurrenceCount());

        TransitionEdge edge = graph.edge(new EdgeKey(
                NormalizedEventType.SAA_SCAN_COMPLETE,
                NormalizedEventType.SAA_SIGNALS_FOUND
        ));
        assertNotNull(edge);
        assertEquals(1L, edge.globalCounter().rawCount());

        SystemEpisode episode =
                harness.service.activeEpisode(SHIP_9).orElseThrow();
        EventOccurrence survey = episode.timeline().stream()
                .filter(occurrence -> occurrence.eventType()
                        .equals(NormalizedEventType.SAA_SCAN_COMPLETE))
                .findFirst()
                .orElseThrow();
        assertEquals(
                1001L,
                survey.attributes().get("SystemAddress").longValue(),
                "the normalized attributes reached the occurrence"
        );
        assertEquals(4, survey.attributes().get("BodyID").intValue());
        assertEquals(
                "Test A 1",
                survey.attributes().get("BodyName").textValue()
        );
        assertEquals(2, survey.attributes().get("ProbesUsed").intValue());
        assertEquals(
                3,
                survey.attributes().get("EfficiencyTarget").intValue()
        );

        // Persistence needs no schema change and gets none: a normalized type
        // is stored as its own validated string, with no version field and no
        // allowlist to extend. Round-tripping is covered by
        // JsonBehaviorGraphStoreTest for occurrences generally; this event has
        // nothing about it that the store treats differently.
        assertEquals(5L, survey.episodeSequence());
    }

    private static boolean hasNode(
            ShipBehaviorGraph graph,
            NormalizedEventType eventType
    ) {
        return graph.nodes().stream().anyMatch(node ->
                node.eventType().equals(eventType));
    }

    @Test
    void leaveBodyCreatesConcreteStepAndSplitsTravelEdges() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T17:00:00Z","event":"FSDJump",
                 "StarSystem":"Departure","SystemAddress":1101}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:00:01Z","event":"ApproachBody",
                 "StarSystem":"Departure","SystemAddress":1101,
                 "Body":"Departure 2","BodyID":2}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:00:02Z","event":"LeaveBody",
                 "StarSystem":"Departure","SystemAddress":1101,
                 "Body":"Departure 2","BodyID":2}
                """);

        SystemEpisode beforeTarget =
                harness.service.activeEpisode(SHIP_9).orElseThrow();
        EventOccurrence leave = beforeTarget.timeline().getLast();
        assertEquals(NormalizedEventType.LEAVE_BODY, leave.eventType());
        assertEquals("LeaveBody", leave.originalEventName());
        assertEquals("Departure 2", leave.attributes().get("Body").asText());
        assertEquals(2, leave.context().bodyId());
        assertEquals("Departure 2", leave.context().bodyName());
        assertEquals(FlightMode.SUPERCRUISE, leave.context().flightMode());
        assertEquals(
                leave.id(),
                harness.service.cursor(SHIP_9).orElseThrow().occurrenceId()
        );

        harness.accept("""
                {"timestamp":"2026-07-24T17:00:03Z","event":"FSDTarget",
                 "Name":"Next System","SystemAddress":1102,
                 "RemainingJumpsInRoute":1}
                """);

        SystemEpisode episode =
                harness.service.activeEpisode(SHIP_9).orElseThrow();
        assertEquals(
                List.of(
                        NormalizedEventType.SYSTEM_ENTRY,
                        NormalizedEventType.APPROACH_BODY,
                        NormalizedEventType.LEAVE_BODY,
                        NormalizedEventType.FSD_TARGET_SELECTED
                ),
                episode.timeline().stream()
                        .map(EventOccurrence::eventType)
                        .toList()
        );
        assertNull(episode.timeline().getLast().context().bodyId());
        ShipBehaviorGraph graph = harness.graph(SHIP_9);
        assertNotNull(graph.edge(new EdgeKey(
                NormalizedEventType.APPROACH_BODY,
                NormalizedEventType.LEAVE_BODY
        )));
        assertNotNull(graph.edge(new EdgeKey(
                NormalizedEventType.LEAVE_BODY,
                NormalizedEventType.FSD_TARGET_SELECTED
        )));
    }

    @Test
    void researchedJournalActivitiesCreateStepsWithoutRepeatInflation() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T17:30:00Z","event":"FSDJump",
                 "StarSystem":"Projection","SystemAddress":1151}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:30:01Z",
                 "event":"DockingRequested","StationName":"Research Port",
                 "StationType":"Coriolis","MarketID":17,
                 "LandingPads":{"Small":2,"Medium":4,"Large":1}}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:30:02Z",
                 "event":"DockingGranted","StationName":"Research Port",
                 "StationType":"Coriolis","MarketID":17,"LandingPad":32}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:30:03Z",
                 "event":"LaunchDrone","Type":"Recon"}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:30:04Z",
                 "event":"MaterialCollected","Category":"Encoded",
                 "Name":"symmetrickeys","Count":3}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:30:11Z",
                 "event":"MaterialCollected","Category":"Encoded",
                 "Name":"embeddedfirmware","Count":6}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:30:20Z",
                 "event":"UnderAttack","Target":"You"}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:31:20Z",
                 "event":"UnderAttack","Target":"You"}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:31:21Z",
                 "event":"UnderAttack","Target":"Fighter"}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:31:22Z",
                 "event":"FuelScoop","Scooped":5.0,"Total":15.5}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:31:30Z",
                 "event":"FuelScoop","Scooped":0.5,"Total":16.0}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:31:31Z",
                 "event":"FSSDiscoveryScan","SystemAddress":1151,
                 "BodyCount":3,"NonBodyCount":0}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:31:32Z",
                 "event":"FuelScoop","Scooped":1.0,"Total":16.0}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T17:31:33Z",
                 "event":"LaunchDrone","Type":"FutureExperimental"}
                """);

        SystemEpisode episode =
                harness.service.activeEpisode(SHIP_9).orElseThrow();
        assertEquals(
                List.of(
                        NormalizedEventType.SYSTEM_ENTRY,
                        NormalizedEventType.DOCKING_REQUESTED,
                        NormalizedEventType.DOCKING_GRANTED,
                        NormalizedEventType.RECON_LIMPET_LAUNCHED,
                        NormalizedEventType.MATERIAL_COLLECTED,
                        NormalizedEventType.UNDER_ATTACK,
                        NormalizedEventType.UNDER_ATTACK,
                        NormalizedEventType.FUEL_SCOOPING,
                        NormalizedEventType.FSS_DISCOVERY_SCAN,
                        NormalizedEventType.FUEL_SCOOPING,
                        NormalizedEventType.LIMPET_LAUNCHED
                ),
                episode.timeline().stream()
                        .map(EventOccurrence::eventType)
                        .toList()
        );

        List<EventOccurrence> materials = episode.timeline().stream()
                .filter(occurrence -> occurrence.eventType().equals(
                        NormalizedEventType.MATERIAL_COLLECTED))
                .toList();
        assertEquals(1, materials.size());
        assertEquals(
                "symmetrickeys",
                materials.getFirst().attributes().get("Name").asText()
        );

        List<EventOccurrence> attacks = episode.timeline().stream()
                .filter(occurrence -> occurrence.eventType().equals(
                        NormalizedEventType.UNDER_ATTACK))
                .toList();
        assertEquals(2, attacks.size());
        assertEquals(
                List.of("You", "Fighter"),
                attacks.stream()
                        .map(occurrence ->
                                occurrence.attributes().get("Target").asText())
                        .toList()
        );

        EventOccurrence dockingRequest = episode.timeline().get(1);
        assertEquals(
                4,
                dockingRequest.attributes()
                        .get("LandingPads")
                        .get("Medium")
                        .asInt()
        );
        assertEquals(
                32,
                episode.timeline().get(2)
                        .attributes()
                        .get("LandingPad")
                        .asInt()
        );
        assertEquals(
                "FutureExperimental",
                episode.timeline().getLast()
                        .attributes()
                        .get("Type")
                        .asText()
        );

        ShipBehaviorGraph graph = harness.graph(SHIP_9);
        assertEquals(
                2,
                graph.nodes().stream()
                        .filter(node -> node.eventType().equals(
                                NormalizedEventType.FUEL_SCOOPING))
                        .findFirst()
                        .orElseThrow()
                        .rawOccurrenceCount()
        );
        assertNull(graph.edge(new EdgeKey(
                NormalizedEventType.MATERIAL_COLLECTED,
                NormalizedEventType.MATERIAL_COLLECTED
        )));
        assertNull(graph.edge(new EdgeKey(
                NormalizedEventType.FUEL_SCOOPING,
                NormalizedEventType.FUEL_SCOOPING
        )));
        assertNotNull(graph.edge(new EdgeKey(
                NormalizedEventType.RECON_LIMPET_LAUNCHED,
                NormalizedEventType.MATERIAL_COLLECTED
        )));
    }

    @Test
    void biologicalFixtureSeparatesOneAndSevenSignalEdgeCounters()
            throws IOException {
        Harness harness = new Harness();
        harness.acceptFixture("biological-contexts.jsonl");

        ShipBehaviorGraph graph = harness.graph(SHIP_9);
        TransitionEdge approach = graph.edge(new EdgeKey(
                NormalizedEventType.SAA_SIGNALS_FOUND,
                NormalizedEventType.APPROACH_BODY
        ));
        TransitionEdge target = graph.edge(new EdgeKey(
                NormalizedEventType.SAA_SIGNALS_FOUND,
                NormalizedEventType.FSD_TARGET_SELECTED
        ));
        assertNotNull(approach);
        assertNotNull(target);
        assertEquals(3, approach.globalCounter().rawCount());
        assertEquals(
                3,
                approach.contextCounter(new ContextKey(
                                "bioSignals=7|landable=unknown"))
                        .orElseThrow()
                        .rawCount()
        );
        assertEquals(
                1,
                target.contextCounter(new ContextKey(
                                "bioSignals=1|landable=unknown"))
                        .orElseThrow()
                        .rawCount()
        );
    }

    @Test
    void repeatedExobiologyRouteOutweighsRareTouchdownLiftoffBranch()
            throws IOException {
        Harness harness = new Harness();
        harness.acceptFixture("exobiology.jsonl");
        harness.acceptFixture("touchdown-liftoff.jsonl");

        ShipBehaviorGraph graph = harness.graph(SHIP_9);
        TransitionEdge disembark = graph.edge(new EdgeKey(
                NormalizedEventType.TOUCHDOWN,
                NormalizedEventType.DISEMBARK
        ));
        TransitionEdge liftoff = graph.edge(new EdgeKey(
                NormalizedEventType.TOUCHDOWN,
                NormalizedEventType.LIFTOFF
        ));
        assertEquals(2, disembark.globalCounter().rawCount());
        assertEquals(1, liftoff.globalCounter().rawCount());
        Instant evaluationTime = Instant.parse("2026-07-24T17:01:00Z");
        assertTrue(
                disembark.globalCounter().valueAt(
                        evaluationTime,
                        Duration.ofDays(30)
                ) > liftoff.globalCounter().valueAt(
                        evaluationTime,
                        Duration.ofDays(30)
                )
        );
    }

    @Test
    void commanderIdentityChangeClosesTheActuallyActiveShipGraph() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T21:00:01Z","event":"FSDJump",
                 "StarSystem":"Old Commander","SystemAddress":6001}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T21:00:02Z","event":"Commander",
                 "FID":"F99999999","Name":"Other"}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T21:00:03Z","event":"LoadGame",
                 "FID":"F99999999","ShipID":1,"Ship":"sidewinder"}
                """);

        SystemEpisode oldEpisode =
                harness.service.episodes(SHIP_9).getFirst();
        assertEquals(
                EpisodeCompletionReason.SHIP_SWITCH,
                oldEpisode.completionReason()
        );
        assertNull(harness.graph(SHIP_9).cursor());
        assertTrue(
                harness.graph(new GraphId("F99999999", 1))
                        .nodes()
                        .isEmpty()
        );
    }

    @Test
    void predictionQueryRejectsContextFromAnotherShipGraph() {
        Harness harness = new Harness();
        harness.identifyShip9();
        BehaviorGraphQueryService query =
                new BehaviorGraphQueryService(harness.service);

        assertThrows(IllegalArgumentException.class, () ->
                query.predictNext(
                        SHIP_14,
                        harness.service.currentContext(),
                        Instant.parse("2026-07-24T22:00:00Z"),
                        3
                ));
    }

    @Test
    void locationIsClassifiedAsAnEpisodeBoundary() {
        Harness harness = new Harness();
        harness.identifyShip9();
        PublishedObservation<JournalEventObservation> location =
                harness.accept("""
                        {"timestamp":"2026-07-24T23:00:01Z",
                         "event":"Location","StarSystem":"Boundary",
                         "SystemAddress":7001}
                        """);

        assertEquals(
                EventSignificance.BOUNDARY,
                new EventSignificancePolicy().classify(location.payload())
        );
    }

    @Test
    void statusDeltasJoinTheExactEpisodePathInSingleWriterOrder() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.accept("""
                {"timestamp":"2026-07-24T23:10:00Z","event":"FSDJump",
                 "StarSystem":"Mixed Sources","SystemAddress":7101}
                """);
        harness.acceptStatus("""
                {"timestamp":"2026-07-24T23:10:01Z","event":"Status",
                 "Flags":0,"GuiFocus":0}
                """);
        harness.accept("""
                {"timestamp":"2026-07-24T23:10:02Z",
                 "event":"FSSDiscoveryScan","SystemAddress":7101,
                 "BodyCount":5,"NonBodyCount":0}
                """);
        PublishedObservation<StatusSnapshotObservation> fss =
                harness.acceptStatus("""
                        {"timestamp":"2026-07-24T23:10:02Z",
                         "event":"Status","Flags":0,"GuiFocus":9}
                        """);
        PublishedObservation<StatusSnapshotObservation> saaAndGear =
                harness.acceptStatus("""
                        {"timestamp":"2026-07-24T23:10:02Z",
                         "event":"Status","Flags":4,"GuiFocus":10}
                        """);
        harness.acceptStatus("""
                {"timestamp":"2026-07-24T23:10:03Z","event":"Status",
                 "Flags":4,"GuiFocus":10}
                """);

        SystemEpisode episode =
                harness.service.activeEpisode(SHIP_9).orElseThrow();
        assertEquals(
                List.of(
                        NormalizedEventType.SYSTEM_ENTRY,
                        NormalizedEventType.FSS_DISCOVERY_SCAN,
                        NormalizedEventType.FSS_MODE_ENTERED,
                        NormalizedEventType.FSS_MODE_EXITED,
                        NormalizedEventType.SAA_MODE_ENTERED,
                        NormalizedEventType.LANDING_GEAR_DEPLOYED
                ),
                episode.timeline().stream()
                        .map(EventOccurrence::eventType)
                        .toList()
        );
        assertEquals(
                List.of(0L, 1L, 2L, 3L, 4L, 5L),
                episode.timeline().stream()
                        .map(EventOccurrence::episodeSequence)
                        .toList()
        );
        assertEquals(
                BehaviorGraphIds.statusOccurrence(
                        SHIP_9,
                        fss.observationId(),
                        NormalizedEventType.FSS_MODE_ENTERED
                ),
                episode.timeline().get(2).id()
        );
        assertEquals(
                BehaviorGraphIds.statusOccurrence(
                        SHIP_9,
                        saaAndGear.observationId(),
                        NormalizedEventType.LANDING_GEAR_DEPLOYED
                ),
                episode.timeline().getLast().id()
        );
        assertEquals(
                NormalizedEventType.LANDING_GEAR_DEPLOYED,
                harness.graph(SHIP_9).cursor().eventType()
        );
        assertEquals(
                episode.timeline().stream()
                        .skip(1)
                        .map(EventOccurrence::eventType)
                        .toList(),
                episode.occurrenceTransitions().stream()
                        .map(transition -> transition.toEventType())
                        .toList()
        );
    }

    @Test
    void statusDeltasWithoutActiveEpisodeAreNotRecordedOrDelayed() {
        Harness harness = new Harness();
        harness.identifyShip9();
        harness.acceptStatus("""
                {"timestamp":"2026-07-24T23:20:00Z","event":"Status",
                 "Flags":0,"GuiFocus":0}
                """);
        harness.acceptStatus("""
                {"timestamp":"2026-07-24T23:20:01Z","event":"Status",
                 "Flags":4,"GuiFocus":9}
                """);
        assertTrue(harness.graph(SHIP_9).nodes().isEmpty());

        harness.accept("""
                {"timestamp":"2026-07-24T23:20:02Z","event":"FSDJump",
                 "StarSystem":"After Baseline","SystemAddress":7201}
                """);
        harness.acceptStatus("""
                {"timestamp":"2026-07-24T23:20:03Z","event":"Status",
                 "Flags":0,"GuiFocus":0}
                """);

        SystemEpisode episode =
                harness.service.activeEpisode(SHIP_9).orElseThrow();
        assertEquals(
                List.of(
                        NormalizedEventType.SYSTEM_ENTRY,
                        NormalizedEventType.FSS_MODE_EXITED,
                        NormalizedEventType.LANDING_GEAR_RETRACTED
                ),
                episode.timeline().stream()
                        .map(EventOccurrence::eventType)
                        .toList()
        );
    }

    private static void assertRootFor(
            SystemEpisode episode,
            PublishedObservation<JournalEventObservation> source
    ) {
        EventOccurrence root = episode.timeline().stream()
                .filter(occurrence -> occurrence.eventType().equals(
                        NormalizedEventType.SYSTEM_ENTRY))
                .findFirst()
                .orElseThrow();
        assertEquals(1, episode.timeline().stream()
                .filter(occurrence -> occurrence.eventType().equals(
                        NormalizedEventType.SYSTEM_ENTRY))
                .count());
        assertEquals("FSDJump", root.originalEventName());
        assertEquals(
                BehaviorGraphIds.journalOccurrence(
                        episode.graphId(),
                        source.observationId()
                ),
                root.id()
        );
        assertEquals(root.id(), episode.rootOccurrenceId());
    }

    /**
     * A visit Kairon has already recorded is adopted whole, cursor included.
     *
     * <p>A restored visit cannot be rebuilt from the journal, because six of
     * its occurrence types come from {@code Status.json} and no journal
     * contains them: the live episode this was found on held 48 journal
     * occurrences and 6 status ones and ended on a landing-gear deployment. So
     * the persisted episode is adopted rather than replayed — and adopting it
     * without its position is what a live run did on 2026-08-07: an episode of
     * fifty-four occurrences beside a graph with no cursor, and four hundred
     * {@code active episode has no graph cursor} failures in one minute, the
     * graph recording nothing for the rest of the session.</p>
     *
     * <h2>Three conditions, and the defect needs all three</h2>
     * <p>A real {@link JsonBehaviorGraphStore}, because an in-memory one hands
     * the same objects back and the pair never reaches disk. A <em>completed</em>
     * episode, because {@code store.loadEpisode} reads the archive and a clean
     * shutdown is what puts it there. And {@code BOOTSTRAP} capture, because
     * {@code REPLAY} takes a different branch entirely — it re-projects the
     * episode progressively and carries a cursor with it. Four attempts missing
     * one condition each passed against the broken code.</p>
     */
    @Test
    void aSessionAlreadyRecordedIsRestoredWithoutLosingItsCursor(
            @TempDir Path directory
    ) {
        // The real store, because this defect is about what survives on disk
        // between two runs and an in-memory one hands the same objects back.
        BehaviorGraphStore store = new JsonBehaviorGraphStore(directory);
        Harness first = new Harness(store);
        first.identifyShip9();
        PublishedObservation<JournalEventObservation> located =
                first.accept(RESTORE_LOCATION);
        SystemEpisodeId episodeId = BehaviorGraphIds.restoredEpisode(
                SHIP_9,
                located.observationId()
        );
        first.accept(RESTORE_TOUCHDOWN);
        first.accept(RESTORE_LIFTOFF);
        assertEquals(
                2,
                first.service.activeEpisode(SHIP_9).orElseThrow()
                        .timeline().size()
        );
        // A clean shutdown: the episode is completed and archived, and the
        // graph is written with no cursor because a completed episode has
        // none. Both files are correct on their own.
        first.completeReplay();
        assertNull(store.loadGraph(SHIP_9).orElseThrow().cursor());
        assertEquals(
                2,
                store.loadEpisode(episodeId).orElseThrow().timeline().size(),
                "the visit is archived under the id its Location record mints"
        );

        // The restart, captured the way a restart captures: BOOTSTRAP, not
        // REPLAY. The distinction is the whole of this defect — the replay
        // path re-projects a persisted episode progressively and carries a
        // cursor with it, and the bootstrap path adopted the episode whole.
        Harness second = new Harness(store);
        // Byte-for-byte the record the first run read, because the episode id
        // is derived from the observation id and the observation id from the
        // record's place in the file. A restart reads the same file.
        second.bootstrap("""
                {"timestamp":"2026-07-24T11:00:00Z",
                 "event":"LoadGame","FID":"F12345678",
                 "ShipID":9,"Ship":"explorer_nx"}
                """);
        // The same Location record: its observation id is the episode id, so
        // this is the moment the persisted episode is adopted.
        PublishedObservation<JournalEventObservation> restore =
                second.bootstrap(RESTORE_LOCATION);
        assertNotNull(
                second.capture(restore),
                "an adopted episode is adopted with its cursor"
        );

        // And one the store has never seen, which is where the live run threw.
        PublishedObservation<JournalEventObservation> landing = second.bootstrap("""
                {"timestamp":"2026-07-24T12:10:00Z","event":"Touchdown",
                 "PlayerControlled":true,"StarSystem":"Restore A",
                 "SystemAddress":2001,"Body":"Restore A 1","BodyID":5,
                 "OnStation":false,"OnPlanet":true}
                """);
        // The capture the coordinator performs for every observation, which is
        // where the live run threw four hundred times.
        assertNotNull(second.capture(landing));

        GraphCursor cursor = second.service.cursor(SHIP_9).orElseThrow();
        SystemEpisode episode =
                second.service.activeEpisode(SHIP_9).orElseThrow();
        assertEquals(
                episode.id(),
                cursor.episodeId(),
                "the cursor names an occurrence of the episode it is in"
        );
        assertEquals(
                episode.timeline().getLast().id(),
                cursor.occurrenceId(),
                "and it is the last thing the visit recorded"
        );
    }

    private static final String RESTORE_LOCATION = """
            {"timestamp":"2026-07-24T12:00:01Z","event":"Location",
             "StarSystem":"Restore A","SystemAddress":2001,
             "Body":"Restore A 1","BodyID":5,"Docked":false}
            """;

    private static final String RESTORE_TOUCHDOWN = """
            {"timestamp":"2026-07-24T12:00:02Z","event":"Touchdown",
             "PlayerControlled":true,"StarSystem":"Restore A",
             "SystemAddress":2001,"Body":"Restore A 1","BodyID":5,
             "OnStation":false,"OnPlanet":true}
            """;

    private static final String RESTORE_LIFTOFF = """
            {"timestamp":"2026-07-24T12:00:03Z","event":"Liftoff",
             "PlayerControlled":true,"StarSystem":"Restore A",
             "SystemAddress":2001,"Body":"Restore A 1","BodyID":5,
             "OnStation":false,"OnPlanet":true}
            """;

    private static final class Harness {

        private static final String BASENAME = "Journal.behavior-test.log";
        private static final ObservationSource SOURCE =
                new ObservationSource("elite-journal", "behavior-test");
        private static final ObservationSource STATUS_SOURCE =
                new ObservationSource("elite-status", "behavior-test");
        private static final BehaviorGraphConfiguration CONFIGURATION =
                new BehaviorGraphConfiguration(
                        true,
                        Path.of("target", "behavior-test-unused"),
                        Duration.ofDays(30),
                        2.0,
                        50,
                        false
                );

        private final JournalLineParser parser = new JournalLineParser();
        private final JournalObservationAdapter adapter =
                new JournalObservationAdapter(SOURCE);
        private final StatusSnapshotParser statusParser =
                new StatusSnapshotParser();
        private final StatusObservationAdapter statusAdapter =
                new StatusObservationAdapter(
                        STATUS_SOURCE,
                        "Status.json"
                );
        private final StatusStateDeltaAdapter statusDeltaAdapter =
                new StatusStateDeltaAdapter();
        private final BehaviorGraphStore store;
        private final CurrentGameStateProjector currentGameState =
                new CurrentGameStateProjector();
        /**
         * The current system, applied exactly as the coordinator applies it.
         *
         * <p>Body detail reaches the graph from here rather than from canonical
         * state (ADR-0025). Driving the real registry keeps this harness on the
         * production translation instead of a hand-written stand-in.</p>
         */
        private final CurrentSystemRegistry systemRegistry =
                new CurrentSystemRegistry();
        private BodyDetailLookup lastBodies = BodyDetailLookup.NONE;
        private final BehaviorGraphService service;

        private Harness() {
            this(new InMemoryBehaviorGraphStore());
        }

        private Harness(BehaviorGraphStore store) {
            this.store = store;
            this.service = new BehaviorGraphService(
                        CONFIGURATION,
                        store,
                        new EventSignificancePolicy(),
                        new BehaviorEventNormalizer(),
                        new TransitionContextKeyFactory(),
                        BehaviorGraphListener.NOOP
                );
        }

        private BehaviorGraphApplyResult lastApplyResult;
        private CurrentGameStateSnapshot lastState;
        private long busSequence;
        private long sourceOffset;
        private long statusSequence;

        private void identifyShip9() {
            accept("""
                    {"timestamp":"2026-07-24T11:00:00Z",
                     "event":"LoadGame","FID":"F12345678",
                     "ShipID":9,"Ship":"explorer_nx"}
                    """);
        }

        /** The same record, captured the way a live restart captures it. */
        private PublishedObservation<JournalEventObservation> bootstrap(
                String rawJson
        ) {
            return accept(rawJson, ObservationCaptureMode.BOOTSTRAP);
        }

        private PublishedObservation<JournalEventObservation> accept(
                String rawJson
        ) {
            return accept(rawJson, ObservationCaptureMode.REPLAY);
        }

        private PublishedObservation<JournalEventObservation> accept(
                String rawJson,
                ObservationCaptureMode captureMode
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
                    captureMode,
                    parsed.optionalJournalTimestamp().orElse(Instant.EPOCH)
            );
            PublishedObservation<JournalEventObservation> published =
                    publish(draft, ++busSequence);
            applyToGraph(published);
            return published;
        }

        private PublishedObservation<StatusSnapshotObservation> acceptStatus(
                String rawJson
        ) {
            StatusSnapshotObservation snapshot = assertInstanceOf(
                    ParsedStatusSnapshot.class,
                    statusParser.parse(
                            rawJson.strip().getBytes(StandardCharsets.UTF_8)
                    )
            ).observation();
            ObservationDraft<StatusSnapshotObservation> draft =
                    statusAdapter.adapt(
                            snapshot,
                            statusSequence++,
                            ObservationCaptureMode.LIVE,
                            snapshot.optionalStatusTimestamp().orElseThrow()
                    );
            PublishedObservation<StatusSnapshotObservation> published =
                    new PublishedObservation<>(
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
            CurrentGameStateSnapshot state =
                    currentGameState.currentSnapshot();
            service.onStatusDeltas(
                    published,
                    statusDeltaAdapter.adapt(published),
                    state,
                    bodies(published, state)
            );
            return published;
        }

        private void acceptDuplicate(
                PublishedObservation<JournalEventObservation> original
        ) {
            PublishedObservation<JournalEventObservation> duplicate =
                    new PublishedObservation<>(
                            original.observationId(),
                            ++busSequence,
                            original.source(),
                            original.sourcePosition(),
                            original.sourceTime(),
                            original.observedAt(),
                            original.captureMode(),
                            original.schemaVersion(),
                            original.payload()
                    );
            applyToGraph(duplicate);
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
                            java.util.Optional.empty(),
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
                    projection.currentState(),
                    bodies(observation, projection.currentState())
            );
        }

        private void applyToGraph(
                PublishedObservation<JournalEventObservation> observation
        ) {
            CurrentGameStateProjection projection =
                    currentGameState.applyAndCapture(observation);
            lastApplyResult = service.onObservation(
                    observation,
                    projection.currentState(),
                    projection.observationContext(),
                    bodies(observation, projection.currentState())
            );
            lastState = projection.currentState();
        }

        /**
         * The situation the coordinator would capture for this observation.
         *
         * <p>Through the same query service the runtime uses, because the
         * check that matters here lives in {@code captureSituation} and not in
         * the apply path.</p>
         */
        private BehaviorSituationSnapshot capture(
                PublishedObservation<?> observation
        ) {
            return new BehaviorGraphQueryService(service).capture(
                    observation,
                    lastState,
                    lastApplyResult
            );
        }

        private BodyDetailLookup bodies(
                PublishedObservation<?> observation,
                CurrentGameStateSnapshot state
        ) {
            lastBodies = new RegistryBodyDetail(
                    systemRegistry.applyAndCapture(
                            observation,
                            new VisitIdentity(
                                    state.commanderFid(),
                                    state.shipId(),
                                    state.systemAddress(),
                                    state.systemName()
                            )
                    )
            );
            return lastBodies;
        }


        private void acceptFixture(String name) throws IOException {
            String resource = "/kairon/behavior/fixtures/" + name;
            try (InputStream input =
                         BehaviorGraphServiceTest.class
                                 .getResourceAsStream(resource)) {
                assertNotNull(input, "missing fixture " + resource);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isBlank()) {
                            accept(line);
                        }
                    }
                }
            }
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
