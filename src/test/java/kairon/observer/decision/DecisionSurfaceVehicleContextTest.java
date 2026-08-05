package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.graph.BehaviorGraphApplyStatus;
import kairon.projection.ProjectedObservation;
import kairon.state.CommanderLocationMode;
import kairon.state.CurrentGameStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which vehicle is on the ground changes what a landing is.
 *
 * <p>A ship set down on a body and an SRV driven onto it are different events,
 * and the record itself says only {@code playerControlled}. The kind comes from
 * the situation — and in this journal the first thing that establishes it is a
 * cargo snapshot tagged with the vessel whose hold it describes, arriving six
 * seconds after a deployment that named no type at all.</p>
 *
 * <p>That tag is evidence about the vehicle and nothing more. It never becomes
 * a claim about where the Commander is sitting.</p>
 */
final class DecisionSurfaceVehicleContextTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /** The audited landing, whole, against the real graph and projector. */
    @Test
    void theLandingSaysWhichVehicleIsOnTheGround(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            descend(pipeline);
            List<ProjectedObservation> triggers = pipeline.capturedTriggers();

            assertFalse(
                    triggers.stream().anyMatch(projected ->
                            projected.trigger().payload().getClass()
                                    .getSimpleName().equals("Cargo")),
                    "a cargo snapshot never opens a turn"
            );

            JsonNode request = lastRequest(pipeline);
            assertEquals(
                    List.of("events", "context", "trajectory"),
                    propertyNames(request)
            );
            JsonNode event = request.path("events").get(0);
            assertEquals(
                    "A ship landed on the surface of a planet or moon.",
                    event.path("event").textValue());
            assertEquals(
                    List.of(
                            "event",
                            "body",
                            "playerControlled",
                            "occurrenceOnBody"
                    ),
                    propertyNames(event),
                    "the event payload is unchanged"
            );

            JsonNode context = request.path("context");
            assertEquals(
                    List.of("system", "body", "navigation", "vehicle"),
                    propertyNames(context)
            );
            assertEquals(
                    "SLV",
                    context.path("vehicle").path("kind").textValue(),
                    "launched through the fighter channel, held through the "
                            + "SRV one: a Ship-Launched Vessel"
            );
            assertFalse(
                    context.has("commander"),
                    "a cargo tag does not establish where the Commander is"
            );
            assertEquals(
                    "LANDED",
                    context.path("navigation").path("flightMode").textValue()
            );
            assertEquals(
                    List.of(
                            "A ship in supercruise came within a body's orbital-cruise zone.",
                            "A ship dropped out of supercruise into normal space.",
                            "A vehicle was launched from the ship."
                    ),
                    recent(request)
            );

            String serialized = request.toString();
            assertFalse(serialized.contains("{}"));
            assertFalse(serialized.contains("[]"));
            assertFalse(
                    serialized.contains("\"ID\""),
                    "the runtime identity stays inside Kairon"
            );
            assertFalse(serialized.contains("Nomad"));
        }
    }

    /** The cargo snapshot updates state without touching the graph. */
    @Test
    void theCargoSnapshotIsStateOnly(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            descend(pipeline);

            ProjectedObservation landing =
                    pipeline.capturedTriggers().getLast();
            CurrentGameStateSnapshot state = landing.currentState();
            assertEquals(
                    CurrentGameStateSnapshot.VEHICLE_SLV,
                    state.vehicleKind()
            );
            assertEquals(
                    Long.valueOf(10L),
                    state.activeVehicleId(),
                    "the runtime identity is untouched"
            );
            assertEquals(CommanderLocationMode.SHIP, state.commanderMode());

            assertFalse(
                    landing.behaviorSituation()
                            .activeEpisode()
                            .orElseThrow()
                            .trajectory()
                            .stream()
                            .anyMatch(occurrence -> occurrence.eventType()
                                    .value().contains("CARGO")),
                    "no cargo occurrence is recorded"
            );
            assertEquals(
                    BehaviorGraphApplyStatus.APPLIED,
                    landing.graphResult().status(),
                    "the landing itself is still structural"
            );
        }
    }

    /** Lifting off in the same vehicle reports the same kind. */
    @Test
    void aLiftoffCarriesTheKnownVehicleToo(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            descend(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-24T16:55:00Z","event":"Liftoff",
                     "PlayerControlled":true,
                     "StarSystem":"Schieni GG-A c3-84",
                     "SystemAddress":23155945939738,
                     "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                     "OnStation":false,"OnPlanet":true}
                    """);
            pipeline.settleProjection();

            JsonNode request = lastRequest(pipeline);
            assertEquals(
                    "A ship took off from the surface of a planet or moon.",
                    request.path("events").get(0).path("event").textValue()
            );
            assertEquals(
                    "SLV",
                    request.path("context").path("vehicle").path("kind")
                            .textValue()
            );
            assertFalse(request.path("context").has("commander"));
        }
    }

    /**
     * An unknown vehicle is absent, not reported as unknown.
     *
     * <p>The same landing without the cargo snapshot leaves the kind
     * unestablished, and the group simply does not appear.</p>
     */
    @Test
    void aLandingWithNoEstablishedVehicleSendsNoVehicleGroup(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrive(pipeline);
            pipeline.journal(launch());
            pipeline.journal(touchdown());
            pipeline.settleProjection();

            JsonNode request = lastRequest(pipeline);
            assertEquals(
                    CurrentGameStateSnapshot.VEHICLE_UNKNOWN,
                    pipeline.capturedTriggers().getLast()
                            .currentState()
                            .vehicleKind()
            );
            assertFalse(request.path("context").has("vehicle"));
            assertFalse(request.toString().contains("UNKNOWN"));
            assertEquals(
                    List.of("system", "body", "navigation"),
                    propertyNames(request.path("context"))
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private static void descend(DecisionProductionPipeline pipeline)
            throws Exception {
        arrive(pipeline);
        pipeline.journal(launch());
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:48:51Z","event":"Cargo",
                 "Vessel":"SRV","Count":0,"Inventory":[]}
                """);
        pipeline.journal(touchdown());
        pipeline.settleProjection();
    }

    private static void arrive(DecisionProductionPipeline pipeline) {
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
                {"timestamp":"2026-07-24T16:41:00Z","event":"SupercruiseEntry",
                 "StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155945939738}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:42:00Z","event":"SAAScanComplete",
                 "BodyName":"Schieni GG-A c3-84 4 a",
                 "SystemAddress":23155945939738,"BodyID":20,
                 "ProbesUsed":2,"EfficiencyTarget":2}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:42:01Z","event":"SAASignalsFound",
                 "SystemAddress":23155945939738,"BodyID":20,
                 "BodyName":"Schieni GG-A c3-84 4 a",
                 "Signals":[{"Type":"$SAA_SignalType_Biological;",
                 "Type_Localised":"Biological","Count":1}]}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:42:02Z","event":"Scan",
                 "ScanType":"Detailed","SystemAddress":23155945939738,
                 "BodyID":20,"BodyName":"Schieni GG-A c3-84 4 a",
                 "PlanetClass":"Icy body","Landable":true,
                 "WasDiscovered":false,"WasMapped":false,
                 "WasFootfalled":false,"DistanceFromArrivalLS":1081.453145}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:43:00Z","event":"ApproachBody",
                 "StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155945939738,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:44:00Z","event":"SupercruiseExit",
                 "StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155945939738,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "BodyType":"Planet"}
                """);
    }

    private static String launch() {
        return """
                {"timestamp":"2026-07-24T16:48:45Z","event":"LaunchFighter",
                 "Loadout":"base","ID":10,"PlayerControlled":true}
                """;
    }

    private static String touchdown() {
        return """
                {"timestamp":"2026-07-24T16:49:37Z","event":"Touchdown",
                 "PlayerControlled":true,
                 "StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155945939738,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "OnStation":false,"OnPlanet":true}
                """;
    }

    private JsonNode lastRequest(DecisionProductionPipeline pipeline) {
        List<ProjectedObservation> triggers = pipeline.capturedTriggers();
        for (int index = 0; index < triggers.size() - 1; index++) {
            pipeline.inputsFor(List.of(triggers.get(index)));
        }
        return read(serializer.serialize(factory.create(
                pipeline.inputsFor(List.of(triggers.getLast()))
        )));
    }

    private static List<String> recent(JsonNode request) {
        List<String> recent = new ArrayList<>();
        request.path("trajectory").path("recent")
                .forEach(kind -> recent.add(kind.textValue()));
        return List.copyOf(recent);
    }

    private static List<String> propertyNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }

    private static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
