package kairon.behavior;

import kairon.behavior.model.EpisodeCompletionReason;
import kairon.behavior.model.EpisodeEntrySource;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.EventOccurrenceSource;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.behavior.persistence.BehaviorGraphStore.StoreException;
import kairon.behavior.persistence.InMemoryBehaviorGraphStore;
import kairon.behavior.persistence.JsonBehaviorGraphStore;
import kairon.state.LastKnownShip;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonBehaviorGraphStoreTest {

    private static final GraphId GRAPH_ID = new GraphId("F12345678", 9);
    private static final Instant START =
            Instant.parse("2026-07-24T16:00:00Z");

    @TempDir
    Path temporaryDirectory;

    /**
     * The ship a run ended on, read back by a run that has no Commander yet.
     *
     * <p>One file at the storage root rather than one per Commander, because
     * the caller asking has not read a journal record yet and so has nobody to
     * ask about. It names its own Commander instead, which is what lets a
     * different one be recognised and ignored.</p>
     */
    @Test
    void theLastKnownShipOutlivesTheRunAndNamesItsCommander() {
        JsonBehaviorGraphStore store =
                new JsonBehaviorGraphStore(temporaryDirectory);
        assertTrue(
                store.lastKnownShip().isEmpty(),
                "a store that has recorded nothing remembers nothing"
        );

        store.recordLastKnownShip(
                new LastKnownShip("F12155965", 9L, "explorer_nx", "Caspian")
        );
        store.recordLastKnownShip(
                new LastKnownShip("F12155965", 7L, "lakonminer", null)
        );

        LastKnownShip reopened = new JsonBehaviorGraphStore(temporaryDirectory)
                .lastKnownShip()
                .orElseThrow();
        assertEquals("F12155965", reopened.commanderFid());
        assertEquals(7L, reopened.shipId(), "the last write is the memory");
        assertEquals("lakonminer", reopened.shipType());
        assertNull(reopened.shipName());
        assertTrue(
                Files.isRegularFile(
                        temporaryDirectory.resolve("last-known-ship.json")
                ),
                "at the root, beside the per-Commander directories"
        );
    }

    /** An in-memory store keeps no such memory, and says so. */
    @Test
    void aStoreWithNoMemoryAnswersEmptyRatherThanGuessing() {
        InMemoryBehaviorGraphStore store = new InMemoryBehaviorGraphStore();

        store.recordLastKnownShip(
                new LastKnownShip("F12155965", 9L, "explorer_nx", null)
        );

        assertTrue(store.lastKnownShip().isEmpty());
    }

    /**
     * Pins the exact Phase C.1 provenance limitation.
     *
     * <p>Occurrence provenance is recorded in process and deliberately not
     * persisted, so the store schema is unchanged and needs no migration. The
     * cost is that an occurrence restored from disk reports {@code null} — an
     * honest absence, never a guess. Everything else round-trips.</p>
     */
    @Test
    void inProcessOccurrenceProvenanceIsNotPersistedAndComesBackAbsent()
            throws IOException {
        JsonBehaviorGraphStore store =
                new JsonBehaviorGraphStore(temporaryDirectory);
        SystemEpisode stored = episode("episode-p", "root-p", START);
        EventOccurrence withProvenance = new EventOccurrence(
                stored.timeline().getFirst().id(),
                GRAPH_ID,
                stored.id(),
                0,
                NormalizedEventType.SYSTEM_ENTRY,
                "FSDJump",
                EventOccurrenceSource.JOURNAL,
                START,
                10,
                "Journal.store-test.log",
                Map.of(),
                BehaviorGraphModelTest.context(null, null, "SHIP", null)
        );
        SystemEpisode inProcess = SystemEpisode.startWithRoot(
                stored.id(),
                GRAPH_ID,
                10477373803L,
                "Store Test",
                EpisodeEntrySource.FSD_JUMP,
                withProvenance
        );

        store.saveEpisode(inProcess);
        SystemEpisode loaded = store.loadEpisode(inProcess.id()).orElseThrow();
        EventOccurrence restored = loaded.timeline().getFirst();

        Path file = temporaryDirectory
                .resolve("F12345678")
                .resolve("9")
                .resolve("active-episode.json");
        assertFalse(
                Files.readString(file).contains("\"source\""),
                "the persisted schema is unchanged; no migration is required"
        );
        assertNull(
                restored.source(),
                "absent provenance is stated, never inferred after reload"
        );
        assertEquals(EventOccurrenceSource.JOURNAL, withProvenance.source());
        assertEquals(withProvenance.id(), restored.id());
        assertEquals(withProvenance.eventType(), restored.eventType());
        assertEquals(
                withProvenance.originalEventName(),
                restored.originalEventName()
        );
        assertEquals(withProvenance.timestamp(), restored.timestamp());
        assertEquals(withProvenance.context(), restored.context());
    }

    @Test
    void roundTripsGraphAndMovesCompletedEpisodeToStableLayout()
            throws IOException {
        JsonBehaviorGraphStore store =
                new JsonBehaviorGraphStore(temporaryDirectory);
        SystemEpisode active = episode("episode-a", "root-a", START);
        ShipBehaviorGraph activeGraph = graph(active);

        store.saveEpisode(active);
        store.saveGraph(activeGraph);

        Path shipDirectory = temporaryDirectory
                .resolve("F12345678")
                .resolve("9");
        Path graphFile = shipDirectory.resolve("graph.json");
        Path activeFile = shipDirectory.resolve("active-episode.json");
        assertTrue(Files.isRegularFile(graphFile));
        assertTrue(Files.isRegularFile(activeFile));
        assertEquals(activeGraph, store.loadGraph(GRAPH_ID).orElseThrow());
        assertEquals(active, store.loadEpisode(active.id()).orElseThrow());
        assertEquals(
                active,
                store.loadActiveEpisode(GRAPH_ID).orElseThrow()
        );
        assertEquals(active, store.listEpisodes(GRAPH_ID).getFirst());
        assertEquals(
                active.timeline().getFirst(),
                store.findOccurrence(active.rootOccurrenceId()).orElseThrow()
        );

        SystemEpisode completed = active.complete(
                START.plusSeconds(10),
                EpisodeCompletionReason.REPLAY_COMPLETED
        );
        ShipBehaviorGraph completedGraph = activeGraph
                .withEpisode(completed)
                .withCursor(null);
        store.saveEpisode(completed);
        store.saveGraph(completedGraph);

        Path completedFile = shipDirectory
                .resolve("episodes")
                .resolve("episode-a.json");
        assertFalse(Files.exists(activeFile));
        assertTrue(Files.isRegularFile(completedFile));
        assertEquals(
                completed,
                store.loadEpisode(completed.id()).orElseThrow()
        );
        assertEquals(
                completedGraph,
                store.loadGraph(GRAPH_ID).orElseThrow()
        );
        assertTrue(store.loadActiveEpisode(GRAPH_ID).isEmpty());
        assertTrue(store.loadActiveCursor(GRAPH_ID).isEmpty());
        assertNoTemporaryFiles();
        assertTrue(Files.readString(graphFile).contains(
                ShipBehaviorGraph.SCHEMA_VERSION
        ));
        assertTrue(Files.readString(completedFile).contains(
                SystemEpisode.SCHEMA_VERSION
        ));
    }

    @Test
    void refusesASecondActiveEpisodeAndLeavesOriginalRecoverable()
            throws IOException {
        JsonBehaviorGraphStore store =
                new JsonBehaviorGraphStore(temporaryDirectory);
        SystemEpisode original = episode("episode-a", "root-a", START);
        SystemEpisode competing = episode(
                "episode-b",
                "root-b",
                START.plusSeconds(1)
        );
        store.saveEpisode(original);

        assertThrows(StoreException.class, () ->
                store.saveEpisode(competing));
        assertEquals(original, store.listEpisodes(GRAPH_ID).getFirst());
        assertEquals(1, store.listEpisodes(GRAPH_ID).size());
        assertNoTemporaryFiles();
    }

    @Test
    void inMemoryStoreUsesTheSameSingleActiveEpisodeContract() {
        InMemoryBehaviorGraphStore store =
                new InMemoryBehaviorGraphStore();
        SystemEpisode original = episode("episode-a", "root-a", START);
        SystemEpisode competing = episode(
                "episode-b",
                "root-b",
                START.plusSeconds(1)
        );
        store.saveEpisode(original);

        assertThrows(StoreException.class, () ->
                store.saveEpisode(competing));
        assertEquals(List.of(original), store.listEpisodes(GRAPH_ID));
        assertEquals(
                original,
                store.loadActiveEpisode(GRAPH_ID).orElseThrow()
        );
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (Stream<Path> files = Files.walk(temporaryDirectory)) {
            assertTrue(files.noneMatch(path ->
                    path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static SystemEpisode episode(
            String episodeId,
            String occurrenceId,
            Instant timestamp
    ) {
        SystemEpisodeId id = new SystemEpisodeId(episodeId);
        EventOccurrence root = new EventOccurrence(
                new EventOccurrenceId(occurrenceId),
                GRAPH_ID,
                id,
                0,
                NormalizedEventType.SYSTEM_ENTRY,
                "FSDJump",
                // The store does not carry provenance, so a fixture that is
                // compared to a loaded episode must not claim any.
                null,
                timestamp,
                10,
                "Journal.store-test.log",
                Map.of(),
                BehaviorGraphModelTest.context(null, null, "SHIP", null)
        );
        return SystemEpisode.startWithRoot(
                id,
                GRAPH_ID,
                10477373803L,
                "Store Test",
                EpisodeEntrySource.FSD_JUMP,
                root
        );
    }

    private static ShipBehaviorGraph graph(SystemEpisode episode) {
        EventOccurrence root = episode.timeline().getFirst();
        return ShipBehaviorGraph.empty(
                        GRAPH_ID,
                        "explorer_nx",
                        "Kairon",
                        "loadout-a"
                )
                .recordOccurrence(root)
                .withEpisode(episode)
                .withCursor(new GraphCursor(
                        GRAPH_ID,
                        episode.id(),
                        root.id(),
                        root.eventType(),
                        root.timestamp()
                ));
    }
}
