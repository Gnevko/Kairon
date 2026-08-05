package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.EpisodeEntrySource;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.EventOccurrenceSource;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.behavior.persistence.JsonBehaviorGraphStore;
import kairon.projection.ProjectedObservation;
import kairon.state.FlightMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restoring a session is not arriving somewhere.
 *
 * <p>{@code Location} at startup says where the Commander already is. It used
 * to mint a {@code SYSTEM_ENTRY} occurrence indistinguishable from a completed
 * hyperspace jump, and the first real event of the session then learned an edge
 * out of it — a transition out of a session restart, weighted like anything the
 * Commander had actually done. A measured run turned that single synthetic edge
 * into a {@code likelyNext} of probability one.</p>
 *
 * <p>Everything here runs the production parser, projector and behaviour graph
 * against isolated temporary storage. The provider is a stub that cannot
 * influence what is built.</p>
 */
final class SessionRestoreEpisodeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final GraphId SHIP_9 = new GraphId("F12345678", 9);

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    // ------------------------------------------------------- A. restoration

    /** A1: the visit exists, and holds nothing. */
    @Test
    void locationOpensAnEmptyRestoredVisit(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            restoredSession(pipeline);
            pipeline.settleProjection();

            SystemEpisode episode = pipeline.activeEpisode();
            assertEquals(
                    EpisodeEntrySource.LOCATION_RESTORE,
                    episode.entrySource()
            );
            assertTrue(episode.awaitingFirstOccurrence());
            assertEquals(List.of(), episode.timeline());
            assertNull(episode.rootOccurrenceId());
            assertTrue(pipeline.cursor().isEmpty());
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SYSTEM_ENTRY
                    ),
                    "nothing arrived, so nothing is counted as an arrival"
            );
            assertEquals(
                    List.of(),
                    pipeline.modelInputs(),
                    "a restore is not something to comment on"
            );
        }
    }

    /** A2: the first real event follows nothing. */
    @Test
    void theFirstStructuralEventTakesNoIncomingEdge(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            restoredSession(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:02Z","event":"StartJump",
                     "JumpType":"Supercruise","StarSystem":"Restore A",
                     "SystemAddress":2001}
                    """);
            pipeline.settleProjection();

            SystemEpisode episode = pipeline.activeEpisode();
            assertEquals(
                    List.of(NormalizedEventType.SUPERCRUISE_JUMP_STARTED),
                    pipeline.episodeTypes()
            );
            assertEquals(0L, episode.timeline().getFirst().episodeSequence());
            assertEquals(
                    List.of(),
                    episode.occurrenceTransitions(),
                    "there is no predecessor for it to have followed"
            );
            assertEquals(
                    NormalizedEventType.SUPERCRUISE_JUMP_STARTED,
                    pipeline.cursor().orElseThrow().eventType()
            );
            assertNull(pipeline.edge(
                    NormalizedEventType.SYSTEM_ENTRY,
                    NormalizedEventType.SUPERCRUISE_JUMP_STARTED
            ), "the synthetic startup edge is exactly the defect");
        }
    }

    /** A3: and the model is never told the Commander entered the system. */
    @Test
    void theFirstTurnRemembersNoArrival(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            restoredSession(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:02Z","event":"StartJump",
                     "JumpType":"Supercruise","StarSystem":"Restore A",
                     "SystemAddress":2001}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:03Z",
                     "event":"SupercruiseEntry","StarSystem":"Restore A",
                     "SystemAddress":2001}
                    """);
            pipeline.settleProjection();

            JsonNode request = requestFor(pipeline, "SupercruiseEntry");
            assertEquals(
                    List.of("A frame shift drive began charging for supercruise."),
                    recent(request)
            );
            assertFalse(
                    recent(request).contains("A ship jumped from one star system to another."),
                    "the Commander did not enter this system"
            );
            // The restore established NORMAL_SPACE, the supercruise jump
            // replaced it, and the turn closed on a state that already said
            // SUPERCRUISE. The stale establishment used to arrive as a change
            // and displace the correct value from the context.
            assertEquals(
                    """
                    {"events":[{"event":"A ship entered supercruise from normal space.",\
                    "system":"Restore A"}],\
                    "context":{"navigation":{"flightMode":"SUPERCRUISE"}},\
                    "trajectory":{"recent":["A frame shift drive began charging for supercruise."]}}""",
                    request.toString()
            );
        }
    }

    /** A4: saying it twice changes nothing. */
    @Test
    void aRepeatedLocationInTheSameSystemChangesNothing(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            restoredSession(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:02Z","event":"Location",
                     "StarSystem":"Restore A","SystemAddress":2001,
                     "Docked":false}
                    """);
            pipeline.settleProjection();

            assertEquals(1, pipeline.episodes().size());
            assertTrue(pipeline.activeEpisode().awaitingFirstOccurrence());
            assertTrue(pipeline.cursor().isEmpty());
            assertTrue(
                    pipeline.graph().graph(SHIP_9).orElseThrow()
                            .edges().isEmpty()
            );
        }
    }

    /** A5: and moving without a jump opens another empty visit. */
    @Test
    void aRestoreIntoAnotherSystemOpensAnotherEmptyVisit(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            restoredSession(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:02Z","event":"Location",
                     "StarSystem":"Restore B","SystemAddress":2002,
                     "Docked":false}
                    """);
            pipeline.settleProjection();

            List<SystemEpisode> episodes = pipeline.episodes();
            assertEquals(2, episodes.size());
            for (SystemEpisode episode : episodes) {
                assertEquals(
                        EpisodeEntrySource.LOCATION_RESTORE,
                        episode.entrySource()
                );
                assertTrue(episode.awaitingFirstOccurrence());
                assertNull(episode.rootOccurrenceId());
            }
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SYSTEM_ENTRY
                    )
            );
            assertTrue(
                    pipeline.graph().graph(SHIP_9).orElseThrow()
                            .edges().isEmpty(),
                    "no edge crosses from one visit into another"
            );
        }
    }

    /**
     * A6: an empty visit survives being written down and read back.
     *
     * <p>Asserted at the store and the model, which is where the invariant
     * lives: an episode with no root and no occurrences round-trips, and the
     * first occurrence appended to the reloaded one still takes no
     * transition.</p>
     */
    @Test
    void anEmptyRestoredVisitRoundTripsThroughTheStore(@TempDir Path directory)
            throws Exception {
        JsonBehaviorGraphStore store =
                new JsonBehaviorGraphStore(directory.resolve("graphs"));
        SystemEpisodeId episodeId = new SystemEpisodeId("bge1-restore-a6");
        Instant restoredAt = Instant.parse("2026-07-30T10:00:01Z");
        SystemEpisode empty = SystemEpisode.startRestored(
                episodeId,
                SHIP_9,
                2001L,
                "Restore A",
                restoredAt
        );
        store.saveEpisode(empty);

        SystemEpisode reloaded = store.loadActiveEpisode(SHIP_9).orElseThrow();
        assertEquals(empty, reloaded);
        assertEquals(
                EpisodeEntrySource.LOCATION_RESTORE,
                reloaded.entrySource()
        );
        assertTrue(reloaded.awaitingFirstOccurrence());
        assertNull(reloaded.rootOccurrenceId());
        assertTrue(store.loadActiveCursor(SHIP_9).isEmpty());

        SystemEpisode resumed = reloaded.appendOccurrence(
                occurrence(
                        episodeId,
                        NormalizedEventType.SUPERCRUISE_JUMP_STARTED,
                        restoredAt.plusSeconds(1)
                ),
                null
        );
        assertEquals(1, resumed.timeline().size());
        assertEquals(List.of(), resumed.occurrenceTransitions());
        assertNull(resumed.rootOccurrenceId());
    }

    // ------------------------------------------------------------- B. jumps

    /** B1: a real arrival still has its own root, and no incoming edge. */
    @Test
    void arrivingByHyperspaceStillRecordsAnEntry(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            jumpedSession(pipeline);
            pipeline.settleProjection();

            List<SystemEpisode> episodes = pipeline.episodes();
            assertEquals(2, episodes.size());
            SystemEpisode restored = episodes.get(0);
            SystemEpisode entered = episodes.get(1);
            assertFalse(restored.active());
            assertEquals(
                    EpisodeEntrySource.FSD_JUMP,
                    entered.entrySource()
            );
            assertEquals(
                    NormalizedEventType.SYSTEM_ENTRY,
                    entered.timeline().getFirst().eventType()
            );
            assertEquals(
                    entered.timeline().getFirst().id(),
                    entered.rootOccurrenceId()
            );
            assertEquals(
                    "FSDJump",
                    entered.timeline().getFirst().originalEventName(),
                    "the root is the arrival the journal recorded"
            );
            assertNull(pipeline.edge(
                    NormalizedEventType.HYPERSPACE_JUMP_STARTED,
                    NormalizedEventType.SYSTEM_ENTRY
            ), "no edge crosses from the previous visit into this one");
            assertEquals(
                    "A ship jumped from one star system to another.",
                    DecisionTrajectoryDescriptions.descriptionOf(
                            NormalizedEventType.SYSTEM_ENTRY
                    )
            );
        }
    }

    /** B2: and the ship is in supercruise when it gets there. */
    @Test
    void theJumpArrivesInSupercruise(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            jumpedSession(pipeline);
            pipeline.settleProjection();

            ProjectedObservation jump = triggerOf(pipeline, "FSDJump");
            assertEquals(
                    FlightMode.SUPERCRUISE,
                    jump.currentState().flightMode()
            );

            JsonNode request = requestFor(pipeline, "FSDJump");
            assertEquals(
                    "A ship jumped from one star system to another.",
                    request.path("events").get(0).path("event").textValue()
            );
            assertEquals(
                    "SUPERCRUISE",
                    request.path("context").path("navigation")
                            .path("flightMode").textValue()
            );
            assertFalse(
                    request.path("events").get(0).has("occurrenceOnBody"),
                    "an arrival in a system happened at no body"
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private static void restoredSession(DecisionProductionPipeline pipeline)
            throws Exception {
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"Location",
                 "StarSystem":"Restore A","SystemAddress":2001,
                 "Docked":false}
                """);
    }

    /**
     * A restored session that then leaves under its own power.
     *
     * <p>The supercruise entry is a turn of its own, so what the restore and
     * the first jump established is drained through it. Without that, the
     * jump's turn is the session's first and carries every hidden change since
     * startup — which is the accumulator working as designed, and not what
     * this fixture is about.</p>
     */
    private static void jumpedSession(DecisionProductionPipeline pipeline)
            throws Exception {
        restoredSession(pipeline);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:02Z","event":"StartJump",
                 "JumpType":"Supercruise","StarSystem":"Restore A",
                 "SystemAddress":2001}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:03Z",
                 "event":"SupercruiseEntry","StarSystem":"Restore A",
                 "SystemAddress":2001}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:04Z","event":"StartJump",
                 "JumpType":"Hyperspace","StarSystem":"Restore B",
                 "SystemAddress":2002,"StarClass":"K"}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:05Z","event":"FSDJump",
                 "StarSystem":"Restore B","SystemAddress":2002,
                 "JumpDist":8.5,"FuelUsed":0.4,"FuelLevel":30.2}
                """);
    }

    private static EventOccurrence occurrence(
            SystemEpisodeId episodeId,
            NormalizedEventType eventType,
            Instant at
    ) {
        return new EventOccurrence(
                new EventOccurrenceId("bgo1-" + eventType.value()),
                SHIP_9,
                episodeId,
                0,
                eventType,
                "StartJump",
                EventOccurrenceSource.JOURNAL,
                at,
                1L,
                "Journal.test.log",
                Map.of(),
                new ContextSnapshot(
                        "F12345678", 9L, "explorer_nx", "Wanderer", null,
                        2001L, "Restore A", null, null, null,
                        null, null, null, null, null,
                        null, null, null, null, null,
                        null, null
                )
        );
    }

    private JsonNode requestFor(
            DecisionProductionPipeline pipeline,
            String payloadSimpleName
    ) {
        List<ProjectedObservation> triggers = pipeline.capturedTriggers();
        ProjectedObservation wanted = triggerOf(pipeline, payloadSimpleName);
        for (ProjectedObservation trigger : triggers) {
            if (trigger.busSequence() >= wanted.busSequence()) {
                break;
            }
            pipeline.inputsFor(List.of(trigger));
        }
        return read(serializer.serialize(factory.create(
                pipeline.inputsFor(List.of(wanted))
        )));
    }

    private static ProjectedObservation triggerOf(
            DecisionProductionPipeline pipeline,
            String payloadSimpleName
    ) {
        return pipeline.capturedTriggers().stream()
                .filter(projected -> projected.trigger().payload().getClass()
                        .getSimpleName().equals(payloadSimpleName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        payloadSimpleName + " never became a trigger"
                ));
    }

    private static List<String> recent(JsonNode request) {
        List<String> recent = new ArrayList<>();
        request.path("trajectory").path("recent")
                .forEach(kind -> recent.add(kind.textValue()));
        return List.copyOf(recent);
    }

    private static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
