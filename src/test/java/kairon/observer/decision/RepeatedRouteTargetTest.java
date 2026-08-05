package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.journal.JournalEventObservation;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A route target restated is not a route target chosen.
 *
 * <p>The journal publishes {@code FSDTarget} again for a route that has not
 * changed — the same system, the same remaining jumps, restated around a jump
 * already under way. Both publications used to become occurrences, so the
 * remembered run read {@code ROUTE_TARGET_SELECTED, SUPERCRUISE_JUMP_STARTED,
 * ROUTE_TARGET_SELECTED} for one selection, and the second copy pushed the
 * vehicle recovery out of a three-entry memory to say nothing new.</p>
 *
 * <p>Everything here runs the production parser, projector, graph and
 * projection. The raw record is still parsed, still published and still
 * available to diagnostics; what it no longer does is claim the Commander
 * chose something.</p>
 */
final class RepeatedRouteTargetTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /** The audited sequence, whole. */
    @Test
    void aRestatedTargetAddsNoOccurrenceAndKeepsTheRunReadable(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            recoverVehicle(pipeline);
            pipeline.journal(fsdTarget("2026-07-24T17:00:00Z"));
            pipeline.journal("""
                    {"timestamp":"2026-07-24T17:00:30Z","event":"StartJump",
                     "JumpType":"Supercruise"}
                    """);
            pipeline.journal(fsdTarget("2026-07-24T17:00:40Z"));
            pipeline.journal("""
                    {"timestamp":"2026-07-24T17:01:00Z",
                     "event":"SupercruiseEntry",
                     "StarSystem":"Schieni GG-A c3-84",
                     "SystemAddress":23155945939738}
                    """);
            pipeline.settleProjection();

            // 1: both raw records were parsed and published.
            assertEquals(
                    2,
                    projectionsOf(pipeline, "FSDTarget").size(),
                    "the duplicate is still observed, only not structural"
            );

            // 2-4: the second record establishes nothing new.
            List<ProjectedObservation> targets =
                    projectionsOf(pipeline, "FSDTarget");
            assertTrue(
                    targets.get(1).semanticEnvelope().stateChanges().isEmpty(),
                    "a restatement changes no canonical field"
            );
            assertFalse(
                    targets.get(1).graphResult().changes().occurrenceAdded(),
                    "and adds no occurrence"
            );
            assertTrue(
                    targets.getFirst().graphResult().changes()
                            .occurrenceAdded(),
                    "while the selection itself still does"
            );

            // 5-7: one route-target occurrence, in the expected run.
            assertEquals(
                    1,
                    countOf(pipeline, NormalizedEventType.FSD_TARGET_SELECTED)
            );
            assertEquals(
                    List.of(
                            // The session was restored here, so the visit
                            // records no entry of its own.
                            "AUXILIARY_VEHICLE_LAUNCHED",
                            "AUXILIARY_VEHICLE_DOCKED",
                            "FSD_TARGET_SELECTED",
                            "SUPERCRUISE_JUMP_STARTED",
                            "SUPERCRUISE_ENTRY"
                    ),
                    timeline(pipeline)
            );

            // 8-9: the trajectory the model actually reads, and the rest of
            // the turn unchanged around it.
            String serialized = turnFor(pipeline, "SupercruiseEntry");
            JsonNode request = read(serialized);
            assertEquals(
                    List.of(
                            "A surface vehicle was brought back aboard the ship.",
                            "A star system was selected to jump to.",
                            "A frame shift drive began charging for supercruise."
                    ),
                    texts(request.path("trajectory").path("recent")),
                    "the recovery is back in the three the model remembers"
            );
            assertEquals(
                    """
                    {"events":[{"event":"A ship entered supercruise from normal space.",\
                    "system":"Schieni GG-A c3-84"}],\
                    "context":{"navigation":{"flightMode":"SUPERCRUISE"}},\
                    "trajectory":{"recent":["A surface vehicle was brought back aboard the ship.",\
                    "A star system was selected to jump to.","A frame shift drive began charging for \
                    supercruise."]}}""",
                    serialized
            );
        }
    }

    /**
     * No edge is drawn from the jump to a second selection.
     *
     * <p>The transition the duplicate used to create said the Commander picks a
     * target after starting a jump, which is a habit the graph would then have
     * gone on predicting.</p>
     */
    @Test
    void theJumpLeadsToTheEntryRatherThanBackToATarget(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            recoverVehicle(pipeline);
            pipeline.journal(fsdTarget("2026-07-24T17:00:00Z"));
            pipeline.journal("""
                    {"timestamp":"2026-07-24T17:00:30Z","event":"StartJump",
                     "JumpType":"Supercruise"}
                    """);
            pipeline.journal(fsdTarget("2026-07-24T17:00:40Z"));
            pipeline.journal("""
                    {"timestamp":"2026-07-24T17:01:00Z",
                     "event":"SupercruiseEntry",
                     "StarSystem":"Schieni GG-A c3-84",
                     "SystemAddress":23155945939738}
                    """);
            pipeline.settleProjection();

            assertEquals(
                    List.of("SUPERCRUISE_ENTRY"),
                    successorsOf(
                            pipeline,
                            NormalizedEventType.SUPERCRUISE_JUMP_STARTED
                    )
            );
            assertEquals(
                    List.of("SUPERCRUISE_JUMP_STARTED"),
                    successorsOf(
                            pipeline,
                            NormalizedEventType.FSD_TARGET_SELECTED
                    )
            );
        }
    }

    /** Choosing a different system is still a selection. */
    @Test
    void aDifferentTargetIsStillRecorded(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            recoverVehicle(pipeline);
            pipeline.journal(fsdTarget("2026-07-24T17:00:00Z"));
            pipeline.journal("""
                    {"timestamp":"2026-07-24T17:00:40Z","event":"FSDTarget",
                     "Name":"Colonia","SystemAddress":3238296097059,
                     "StarClass":"K","RemainingJumpsInRoute":4}
                    """);
            pipeline.settleProjection();

            assertEquals(
                    2,
                    countOf(pipeline, NormalizedEventType.FSD_TARGET_SELECTED)
            );
        }
    }

    /** So is the same system one jump closer. */
    @Test
    void aChangedRoutePositionIsStillRecorded(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            recoverVehicle(pipeline);
            pipeline.journal(fsdTarget("2026-07-24T17:00:00Z"));
            pipeline.journal("""
                    {"timestamp":"2026-07-24T17:00:40Z","event":"FSDTarget",
                     "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                     "StarClass":"M","RemainingJumpsInRoute":3}
                    """);
            pipeline.settleProjection();

            assertEquals(
                    2,
                    countOf(pipeline, NormalizedEventType.FSD_TARGET_SELECTED)
            );
        }
    }

    /** A corrected star class is not a decision the Commander made. */
    @Test
    void aMetadataOnlyDifferenceIsNotRecorded(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            recoverVehicle(pipeline);
            pipeline.journal(fsdTarget("2026-07-24T17:00:00Z"));
            pipeline.journal("""
                    {"timestamp":"2026-07-24T17:00:40Z","event":"FSDTarget",
                     "Name":"SCHIENI GG-A C3-64","SystemAddress":23155945939738,
                     "StarClass":"K","RemainingJumpsInRoute":4}
                    """);
            pipeline.settleProjection();

            assertEquals(
                    1,
                    countOf(pipeline, NormalizedEventType.FSD_TARGET_SELECTED)
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    /** Arrive, put a vehicle out and recover it: the run before the route. */
    private static void recoverVehicle(DecisionProductionPipeline pipeline) {
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:40:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:40:01Z","event":"Location",
                 "StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155945939738,"Docked":false}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:48:45Z","event":"LaunchFighter",
                 "Loadout":"base","ID":10,"PlayerControlled":true}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:56:00Z","event":"DockSRV",
                 "ID":10,"SRVType":"lander01","SRVType_Localised":"Nomad"}
                """);
    }

    private static String fsdTarget(String timestamp) {
        return """
                {"timestamp":"%s","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "StarClass":"M","RemainingJumpsInRoute":4}
                """.formatted(timestamp);
    }

    // -------------------------------------------------------------- reading

    private static List<String> timeline(DecisionProductionPipeline pipeline) {
        List<String> types = new ArrayList<>();
        for (EventOccurrence occurrence : episode(pipeline).timeline()) {
            types.add(occurrence.eventType().value());
        }
        return List.copyOf(types);
    }

    private static long countOf(
            DecisionProductionPipeline pipeline,
            NormalizedEventType eventType
    ) {
        return episode(pipeline).timeline().stream()
                .filter(occurrence -> occurrence.eventType().equals(eventType))
                .count();
    }

    /** Every type the graph has drawn an edge to from this one. */
    private static List<String> successorsOf(
            DecisionProductionPipeline pipeline,
            NormalizedEventType eventType
    ) {
        return pipeline.graph()
                .outgoingEdges(
                        pipeline.graphId(),
                        eventType,
                        java.time.Instant.parse("2026-07-24T17:02:00Z")
                )
                .stream()
                .map(edge -> edge.toEventType().value())
                .sorted()
                .toList();
    }

    private static SystemEpisode episode(DecisionProductionPipeline pipeline) {
        return pipeline.graph()
                .activeEpisode(pipeline.graphId())
                .orElseThrow();
    }

    private static List<ProjectedObservation> projectionsOf(
            DecisionProductionPipeline pipeline,
            String journalEvent
    ) {
        List<ProjectedObservation> matches = new ArrayList<>();
        for (ProjectedObservation projected : pipeline.capturedProjections()) {
            if (projected.trigger().payload()
                    instanceof JournalEventObservation event
                    && event.getClass().getSimpleName().equals(journalEvent)) {
                matches.add(projected);
            }
        }
        return List.copyOf(matches);
    }

    /** The request one turn would build from that record alone. */
    private String turnFor(
            DecisionProductionPipeline pipeline,
            String journalEvent
    ) {
        List<ProjectedObservation> triggers = pipeline.capturedTriggers();
        ProjectedObservation wanted = null;
        for (ProjectedObservation trigger : triggers) {
            if (trigger.trigger().payload()
                    instanceof JournalEventObservation event
                    && event.getClass().getSimpleName().equals(journalEvent)) {
                wanted = trigger;
                break;
            }
        }
        if (wanted == null) {
            throw new IllegalStateException("no " + journalEvent + " trigger");
        }
        for (ProjectedObservation trigger : triggers) {
            if (trigger.busSequence() >= wanted.busSequence()) {
                break;
            }
            pipeline.inputsFor(List.of(trigger));
        }
        return serializer.serialize(factory.create(
                pipeline.inputsFor(List.of(wanted))
        ));
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.textValue()));
        return List.copyOf(values);
    }

    private static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
