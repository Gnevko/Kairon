package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.journal.event.ship.LaunchFighter;
import kairon.projection.ProjectedObservation;
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
 * What a {@code LaunchFighter} record actually proves.
 *
 * <p>Observed in a real journal: a {@code LaunchFighter} whose runtime id was
 * later carried by {@code Disembark(SRV=true)}, {@code Embark(SRV=true)} and a
 * {@code DockSRV} naming a Nomad. The launch record itself has no
 * {@code SRVType}, no localised name and nothing else that settles which
 * vehicle went out — so the journal's own event name is not evidence of a
 * fighter.</p>
 *
 * <p>The rule is not the opposite one either. A launch is not an SRV; it is a
 * vehicle whose type is not yet established. Later records that do establish it
 * report it then, and nothing already sent to the model is revised.</p>
 */
final class DecisionVehicleLaunchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /** The launch states what is known and claims no vehicle type. */
    @Test
    void aLaunchFighterRecordDoesNotProveAFighter() {
        JsonNode request = launchTurn();
        JsonNode event = request.path("events").get(0);

        assertEquals(
                List.of("id", "kind", "loadout", "playerControlled"),
                propertyNames(event)
        );
        assertEquals("VEHICLE_LAUNCHED", event.path("kind").textValue());
        assertEquals("base", event.path("loadout").textValue());
        assertTrue(event.path("playerControlled").booleanValue());

        String serialized = request.toString();
        for (String claim : List.of(
                "FIGHTER_LAUNCHED",
                "FIGHTER",
                "Fighter",
                "SRV",
                "SLV",
                "Nomad",
                "lander01",
                "vehicleKind",
                "vehicleType",
                "srvType",
                "inferredType"
        )) {
            assertFalse(
                    serialized.contains(claim),
                    claim + " is not established by this record: " + serialized
            );
        }
    }

    /** The runtime identity is Kairon's, not the model's. */
    @Test
    void theRuntimeVehicleIdIsNeverSent() {
        JsonNode event = launchTurn().path("events").get(0);

        assertFalse(event.has("id_"), "no raw identity under any name");
        assertFalse(event.has("vehicleId"));
        assertFalse(event.has("ID"));
        assertEquals(
                1,
                event.path("id").intValue(),
                "the only id is the local event id, which is 1 here"
        );
    }

    /** The existing commander context is unchanged by the rename. */
    @Test
    void theCommanderIsStillReportedAsAboardTheShip() {
        assertEquals(
                "SHIP",
                launchTurn().path("context").path("commander")
                        .path("presence").textValue()
        );
    }

    /**
     * One vocabulary, forwards and backwards.
     *
     * <p>The graph already normalized this event neutrally; only the current
     * event disagreed with it. A landing that follows a launch must not be told
     * a fighter went out when the launch turn said a vehicle did.</p>
     */
    @Test
    void theRememberedLaunchUsesTheSameNameTheEventDoes() {
        assertEquals(
                DecisionEventCatalog.ruleFor(LaunchFighter.class).kind(),
                DecisionTrajectoryNames.kindOf(
                        NormalizedEventType.AUXILIARY_VEHICLE_LAUNCHED
                )
        );

        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphed(
                        """
                        {"timestamp":"2026-07-30T10:00:03Z",
                         "event":"Touchdown",
                         "StarSystem":"Icy System","SystemAddress":23155,
                         "Body":"Icy One","BodyID":20,
                         "PlayerControlled":true}
                        """,
                        List.of(
                                NormalizedEventType.SYSTEM_ENTRY,
                                NormalizedEventType.SUPERCRUISE_EXIT,
                                NormalizedEventType.AUXILIARY_VEHICLE_LAUNCHED,
                                NormalizedEventType.TOUCHDOWN
                        ),
                        true,
                        0
                )))
        ).request()));

        assertEquals(
                List.of(
                        "SYSTEM_ENTERED",
                        "SUPERCRUISE_EXITED",
                        "VEHICLE_LAUNCHED"
                ),
                recent(request),
                "the launch sits between the drop and the landing"
        );
    }

    /**
     * The whole lifecycle, against the real graph and the real projector.
     *
     * <p>The launch stays neutral, the later SRV records are parsed as usual,
     * the runtime id keeps correlating them internally, and the recovery — the
     * first record that actually names the vehicle — is free to say Nomad.</p>
     */
    @Test
    void laterRecordsIdentifyTheVehicleWithoutRewritingTheLaunch(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                     "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                     "ShipName":"Wanderer"}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:01Z","event":"Location",
                     "StarSystem":"Icy System","SystemAddress":23155,
                     "Docked":false}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:02Z","event":"LaunchFighter",
                     "Loadout":"base","ID":10,"PlayerControlled":true}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:03Z","event":"Cargo",
                     "Vessel":"SRV","Count":0,"Inventory":[]}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:04Z","event":"Touchdown",
                     "StarSystem":"Icy System","SystemAddress":23155,
                     "Body":"Icy One","BodyID":20,"PlayerControlled":true,
                     "OnStation":false,"OnPlanet":true}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:05Z","event":"Disembark",
                     "SRV":true,"ID":10,"StarSystem":"Icy System",
                     "SystemAddress":23155,"Body":"Icy One","BodyID":20,
                     "OnStation":false,"OnPlanet":true}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:06Z","event":"Embark",
                     "SRV":true,"ID":10,"StarSystem":"Icy System",
                     "SystemAddress":23155,"Body":"Icy One","BodyID":20,
                     "OnStation":false,"OnPlanet":true}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:07Z","event":"DockSRV",
                     "ID":10,"SRVType":"lander01","SRVType_Localised":"Nomad"}
                    """);
            pipeline.settleProjection();

            List<ProjectedObservation> triggers = pipeline.capturedTriggers();

            JsonNode launch = requestFor(pipeline, triggers, 0);
            assertEquals(
                    "VEHICLE_LAUNCHED",
                    launch.path("events").get(0).path("kind").textValue()
            );
            assertFalse(launch.toString().contains("Nomad"));

            // The launch record established no type, so nothing downstream may
            // have inferred one from it either.
            CurrentGameStateSnapshot afterLaunch =
                    triggers.getFirst().currentState();
            assertEquals(
                    Long.valueOf(10L),
                    afterLaunch.activeVehicleId(),
                    "the runtime identity is kept for correlation"
            );
            assertEquals("UNKNOWN", afterLaunch.vehicleKind());

            JsonNode recovery = requestFor(
                    pipeline,
                    triggers,
                    triggers.size() - 1
            );
            JsonNode recovered = recovery.path("events").get(0);
            assertEquals(
                    "VEHICLE_RECOVERED",
                    recovered.path("kind").textValue()
            );
            assertEquals(
                    "SLV",
                    recovered.path("vehicleKind").textValue(),
                    "the first record that types the vehicle may say so"
            );
            assertEquals(
                    "Nomad",
                    recovered.path("vehicleType").textValue(),
                    "the model, beside its class rather than instead of it"
            );
            assertFalse(
                    recovery.toString().contains("\"ID\""),
                    "the runtime id still never reaches the model"
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private JsonNode launchTurn() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Location",
                 "StarSystem":"Icy System","SystemAddress":23155,
                 "Docked":false}
                """);
        fixture.inputs(List.of(fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"SupercruiseEntry",
                 "StarSystem":"Icy System","SystemAddress":23155}
                """)));
        return read(serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:02Z",
                         "event":"LaunchFighter","Loadout":"base","ID":10,
                         "PlayerControlled":true}
                        """)))
        ).request()));
    }

    private JsonNode requestFor(
            DecisionProductionPipeline pipeline,
            List<ProjectedObservation> triggers,
            int index
    ) {
        return read(serializer.serialize(factory.create(
                pipeline.inputsFor(List.of(triggers.get(index)))
        ).request()));
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
