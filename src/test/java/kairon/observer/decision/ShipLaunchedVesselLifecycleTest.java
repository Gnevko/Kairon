package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.projection.ProjectedObservation;
import kairon.state.CommanderLocationMode;
import kairon.state.CurrentGameStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static kairon.observer.decision.RequestJson.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Nomad, which the journal describes with three different vocabularies.
 *
 * <p>Frontier launches it as a {@code LaunchFighter}, reports its hold as
 * {@code Cargo(Vessel="SRV")}, boards it with {@code SRV=true} and recovers it
 * with {@code DockSRV}. It is neither a fighter nor a Surface Recon Vehicle: it
 * is a Ship-Launched Vessel, and the model is told so — {@code SLV} for the
 * class, {@code Nomad} for the model — rather than being handed whichever
 * technical channel the game happened to use.</p>
 *
 * <p>No single record proves it. The launch names no type at all, and the cargo
 * tag says only whose hold is being described; together they are a lifecycle no
 * conventional SRV has, because a conventional SRV names itself when it goes
 * out. So the launch turn stays neutral — it is sent before the second record
 * exists and is never rewritten — and the class arrives in time for everything
 * after it.</p>
 */
final class ShipLaunchedVesselLifecycleTest {

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    // ------------------------------------------------------ canonical state

    /**
     * What canonical state says after each record of the audited lifecycle.
     *
     * <p>Read from the projections the production bus actually carried, in
     * order, including the cargo snapshot that never becomes a turn.</p>
     */
    @Test
    void theLifecycleEstablishesTheClassWhenTheEvidenceIsComplete(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            nomadLifecycle(pipeline);

            CurrentGameStateSnapshot launched = stateAfter(pipeline, "LaunchFighter");
            assertEquals(Long.valueOf(10L), launched.activeVehicleId());
            assertEquals(
                    CurrentGameStateSnapshot.VEHICLE_UNKNOWN,
                    launched.vehicleKind(),
                    "the launch record establishes no class"
            );
            assertEquals(
                    CommanderLocationMode.SHIP,
                    launched.commanderMode()
            );

            CurrentGameStateSnapshot held = stateAfter(pipeline, "Cargo");
            assertEquals(Long.valueOf(10L), held.activeVehicleId());
            assertEquals(
                    CurrentGameStateSnapshot.VEHICLE_SLV,
                    held.vehicleKind(),
                    "an ambiguous launch plus an SRV hold is an SLV"
            );
            assertEquals(
                    CommanderLocationMode.SHIP,
                    held.commanderMode(),
                    "whose hold this is says nothing about who is inside it"
            );

            CurrentGameStateSnapshot aboard = stateAfter(pipeline, "Embark");
            assertEquals(Long.valueOf(10L), aboard.activeVehicleId());
            assertEquals(
                    CurrentGameStateSnapshot.VEHICLE_SLV,
                    aboard.vehicleKind(),
                    "SRV=true is the record's form; id 10 is known to be an SLV"
            );
            assertEquals(CommanderLocationMode.SLV, aboard.commanderMode());

            CurrentGameStateSnapshot onFoot =
                    stateAfter(pipeline, "Disembark");
            assertEquals(
                    CurrentGameStateSnapshot.VEHICLE_SLV,
                    onFoot.vehicleKind(),
                    "the vehicle is still there once the Commander steps out"
            );
            assertEquals(
                    CommanderLocationMode.ON_FOOT,
                    onFoot.commanderMode()
            );

            CurrentGameStateSnapshot flying = stateAfter(pipeline, "Liftoff");
            assertEquals(
                    CurrentGameStateSnapshot.VEHICLE_SLV,
                    flying.vehicleKind()
            );
            assertEquals(CommanderLocationMode.SLV, flying.commanderMode());

            CurrentGameStateSnapshot recovered = stateAfter(pipeline, "DockSRV");
            assertNull(recovered.activeVehicleId());
            assertEquals(
                    CurrentGameStateSnapshot.VEHICLE_SHIP,
                    recovered.vehicleKind(),
                    "what the Commander is in now is the ship"
            );
            assertEquals(
                    CommanderLocationMode.SHIP,
                    recovered.commanderMode()
            );
        }
    }

    /**
     * The runtime identity keeps its class after the vehicle is put away.
     *
     * <p>The registry is internal, so it is read the only way it can be: a
     * later launch reusing id 10 resolves to the class recovery recorded
     * against it — {@code SLV}, and never the {@code NOMAD} that class used to
     * be spelled.</p>
     */
    @Test
    void theRecoveredIdentityKeepsItsClass(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            nomadLifecycle(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-24T16:58:00Z","event":"LaunchFighter",
                     "Loadout":"base","ID":10,"PlayerControlled":true}
                    """);
            pipeline.settleProjection();

            CurrentGameStateSnapshot relaunched =
                    pipeline.capturedTriggers().getLast().currentState();
            assertEquals(
                    CurrentGameStateSnapshot.VEHICLE_SLV,
                    relaunched.vehicleKind()
            );
            assertEquals(
                    CommanderLocationMode.SLV,
                    relaunched.commanderMode(),
                    "a known SLV going out again puts the Commander in it"
            );
        }
    }

    // -------------------------------------------------------- model-facing

    /** The launch says a vehicle went out, and nothing it cannot prove. */
    @Test
    void theLaunchTurnStaysNeutral(@TempDir Path directory) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            nomadLifecycle(pipeline);

            String launch = turnFor(pipeline, "LaunchFighter");
            assertEquals(
                    "{\"event\":\"The Commander's ship launched a vehicle it was carrying.\","
                            + "\"commanderControlled\":true}",
                    read(launch).path("events").get(0).toString()
            );
            for (String unproven : List.of(
                    "FIGHTER_LAUNCHED",
                    "SLV",
                    "SRV",
                    "Nomad",
                    "lander01",
                    "vehicleKind",
                    "vehicleType"
            )) {
                assertFalse(
                        launch.contains(unproven),
                        unproven + " is not established yet: " + launch
                );
            }
            assertFalse(launch.contains("\"ID\""));
            assertFalse(launch.contains("vehicleId"));
        }
    }

    /** Landing and lifting off in it say which vehicle is on the ground. */
    @Test
    void theSurfaceTurnsCarryTheVesselClass(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            nomadLifecycle(pipeline);

            for (String movement : List.of("Touchdown", "Liftoff")) {
                JsonNode context = read(turnFor(pipeline, movement))
                        .path("context");
                assertEquals(
                        "SLV",
                        context.path("vehicle").path("kind").textValue(),
                        movement
                );
                assertFalse(
                        turnFor(pipeline, movement)
                                .contains("\"vehicle\":{\"kind\":\"SRV\"}"),
                        movement + " called an SLV an SRV"
                );
            }

            // The whole landing, so the vehicle group is placed among the
            // existing ones rather than merely present.
            assertEquals(
                    """
                    {"events":[{"event":"The Commander's ship landed on the surface of a planet or moon.",\
                    "commanderControlled":true}],\
                    "context":{"body":{"name":"Schieni GG-A c3-84 4 a",\
                    "type":"PLANET","planetClass":"Icy body"},\
                    "vehicle":{"kind":"SLV"}}}""",
                    turnFor(pipeline, "Touchdown")
            );
        }
    }

    /** Getting in and out of it, in the vocabulary of the vessel. */
    @Test
    void thePresenceTurnsNameTheVesselClass(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            nomadLifecycle(pipeline);

            JsonNode disembark = read(turnFor(pipeline, "Disembark"))
                    .path("context");
            assertEquals(
                    "ON_FOOT",
                    disembark.path("commander").path("presence").textValue()
            );
            assertEquals(
                    "SLV",
                    disembark.path("vehicle").path("kind").textValue(),
                    "the vessel is still there once its pilot steps out"
            );

            JsonNode embark = read(turnFor(pipeline, "Embark"))
                    .path("context");
            assertEquals(
                    "SLV",
                    embark.path("commander").path("presence").textValue(),
                    "aboard an SLV is not aboard an SRV"
            );
            assertEquals(
                    "SLV",
                    embark.path("vehicle").path("kind").textValue()
            );

            assertEquals(
                    """
                    {"events":[{"event":"The Commander stepped out of a ship or SRV.",\
                    "system":"Schieni GG-A c3-84",\
                    "onStation":false,"onPlanet":true}],\
                    "context":{"body":{"name":"Schieni GG-A c3-84 4 a"},\
                    "commander":{"presence":"ON_FOOT"},\
                    "vehicle":{"kind":"SLV"}}}""",
                    turnFor(pipeline, "Disembark")
            );
            assertEquals(
                    """
                    {"events":[{"event":"The Commander, on foot, got into a ship or SRV.",\
                    "system":"Schieni GG-A c3-84",\
                    "onStation":false,"onPlanet":true}],\
                    "context":{"body":{"name":"Schieni GG-A c3-84 4 a"},\
                    "commander":{"presence":"SLV"},\
                    "vehicle":{"kind":"SLV"}}}""",
                    turnFor(pipeline, "Embark")
            );
        }
    }

    /**
     * The recovery, whole, against the audited journal.
     *
     * <p>The same lifecycle the audit reproduced, sampling and all, so the one
     * turn that misled the model is pinned byte for byte: a class and a model
     * where an unqualified label used to be, the presence transition the
     * Commander actually made, and no vehicle context to be read as the
     * recovered object.</p>
     */
    @Test
    void theRecoveryTurnIsExact(@TempDir Path directory) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            nomadLifecycle(pipeline, true);

            String recovery = turnFor(pipeline, "DockSRV");
            assertEquals(
                    """
                    {"events":[{"event":"The Commander's ship took a surface vehicle back aboard.",\
                    "vehicleKind":"SLV","vehicleType":"Nomad"}],\
                    "changes":[{"subject":"commander",\
                    "kind":"UPDATED","fields":{"presence":\
                    {"before":"SLV","after":"SHIP"}}}]}""",
                    recovery
            );
            for (String wrong : List.of(
                    "\"vehicle\":\"Nomad\"",
                    "\"vehicleKind\":\"SRV\"",
                    "\"before\":\"SRV\"",
                    "\"context\"",
                    "\"ID\"",
                    "vehicleId",
                    "activeVehicleId"
            )) {
                assertFalse(recovery.contains(wrong), wrong + ": " + recovery);
            }
        }
    }

    // ------------------------------------------------- conventional control

    /**
     * A conventional SRV is still a conventional SRV.
     *
     * <p>It names itself when it goes out, so it never reaches the composite
     * rule, and every stage of its lifecycle keeps the vocabulary it always
     * had. This is the test that says adding a class did not reclassify the
     * fleet.</p>
     */
    @Test
    void aConventionalSurfaceReconVehicleIsUnaffected(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrive(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-24T16:48:45Z","event":"LaunchSRV",
                     "ID":7,"SRVType":"combat_multicrew_srv_01",
                     "SRVType_Localised":"Scarab","PlayerControlled":true}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-24T16:48:51Z","event":"Cargo",
                     "Vessel":"SRV","Count":0,"Inventory":[]}
                    """);
            pipeline.journal(touchdown("2026-07-24T16:49:37Z"));
            pipeline.journal(embark("2026-07-24T16:54:00Z", 7));
            pipeline.journal("""
                    {"timestamp":"2026-07-24T16:56:00Z","event":"DockSRV",
                     "ID":7,"SRVType":"combat_multicrew_srv_01",
                     "SRVType_Localised":"Scarab"}
                    """);
            pipeline.settleProjection();

            CurrentGameStateSnapshot out = stateAfter(pipeline, "LaunchSRV");
            assertEquals(
                    CurrentGameStateSnapshot.VEHICLE_SRV,
                    out.vehicleKind()
            );
            assertEquals(CommanderLocationMode.SRV, out.commanderMode());
            assertEquals(
                    CurrentGameStateSnapshot.VEHICLE_SRV,
                    stateAfter(pipeline, "Cargo").vehicleKind(),
                    "the hold tag adds nothing to a vehicle that named itself"
            );

            assertEquals(
                    "SRV",
                    read(turnFor(pipeline, "Touchdown")).path("context")
                            .path("vehicle").path("kind").textValue()
            );

            JsonNode recovery = read(turnFor(pipeline, "DockSRV"));
            JsonNode event = recovery.path("events").get(0);
            assertEquals(
                    "The Commander's ship took a surface vehicle back aboard.",
                    event.path("event").textValue());
            assertEquals("SRV", event.path("vehicleKind").textValue());
            assertEquals("Scarab", event.path("vehicleType").textValue());
            assertEquals(
                    "SRV",
                    recovery.path("changes").get(0).path("fields")
                            .path("presence").path("before").textValue()
            );
            assertEquals(
                    "SHIP",
                    recovery.path("changes").get(0).path("fields")
                            .path("presence").path("after").textValue()
            );
            assertFalse(turnFor(pipeline, "DockSRV").contains("SLV"));
        }
    }

    /** A recovery that names no type at all sends no type. */
    @Test
    void anUntypedRecoveryOmitsTheModelRatherThanEmptyingIt(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrive(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-24T16:48:45Z","event":"LaunchSRV",
                     "ID":7,"PlayerControlled":true}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-24T16:56:00Z","event":"DockSRV",
                     "ID":7}
                    """);
            pipeline.settleProjection();

            JsonNode event = read(turnFor(pipeline, "DockSRV"))
                    .path("events").get(0);
            assertEquals(
                    "The Commander's ship took a surface vehicle back aboard.",
                    event.path("event").textValue());
            assertFalse(
                    event.has("vehicleKind"),
                    "the record classified nothing, so nothing is claimed"
            );
            assertFalse(event.has("vehicleType"));
            assertFalse(event.has("vehicle"));
        }
    }

    // ------------------------------------------------------ identification

    /** The stable identifier alone identifies a Nomad, in any language. */
    @Test
    void theRawTypeAloneIdentifiesTheVessel(@TempDir Path directory)
            throws Exception {
        JsonNode event = recoveryEvent(directory, """
                {"timestamp":"2026-07-24T16:56:00Z","event":"DockSRV",
                 "ID":7,"SRVType":"LANDER01"}
                """);

        assertEquals("SLV", event.path("vehicleKind").textValue());
        assertEquals(
                "LANDER01",
                event.path("vehicleType").textValue(),
                "with no label to speak, the record's own word is the best "
                        + "available, exactly as every other label falls back"
        );
    }

    /** The label alone does too, and reads better when it is there. */
    @Test
    void theLocalisedLabelAloneIdentifiesTheVessel(@TempDir Path directory)
            throws Exception {
        JsonNode event = recoveryEvent(directory, """
                {"timestamp":"2026-07-24T16:56:00Z","event":"DockSRV",
                 "ID":7,"SRVType_Localised":"nomad"}
                """);

        assertEquals("SLV", event.path("vehicleKind").textValue());
        assertEquals("nomad", event.path("vehicleType").textValue());
    }

    /** Any other named vessel is a conventional SRV. */
    @Test
    void anotherNamedVesselIsAConventionalSurfaceReconVehicle(
            @TempDir Path directory
    ) throws Exception {
        JsonNode event = recoveryEvent(directory, """
                {"timestamp":"2026-07-24T16:56:00Z","event":"DockSRV",
                 "ID":7,"SRVType":"testbuggy","SRVType_Localised":"Scarab"}
                """);

        assertEquals("SRV", event.path("vehicleKind").textValue());
        assertEquals("Scarab", event.path("vehicleType").textValue());
    }

    // ------------------------------------------------------------- fixtures

    private JsonNode recoveryEvent(Path directory, String dockRecord)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrive(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-24T16:48:45Z","event":"LaunchSRV",
                     "ID":7,"PlayerControlled":true}
                    """);
            pipeline.journal(dockRecord);
            pipeline.settleProjection();
            return read(turnFor(pipeline, "DockSRV"))
                    .path("events").get(0);
        }
    }

    private static void nomadLifecycle(DecisionProductionPipeline pipeline)
            throws Exception {
        nomadLifecycle(pipeline, false);
    }

    /**
     * The audited lifecycle.
     *
     * @param sampling whether to run the biological sequence between getting
     *                 out and getting back in, as the audited journal did
     */
    private static void nomadLifecycle(
            DecisionProductionPipeline pipeline,
            boolean sampling
    ) throws Exception {
        arrive(pipeline);
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:48:45Z","event":"LaunchFighter",
                 "Loadout":"base","ID":10,"PlayerControlled":true}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:48:51Z","event":"Cargo",
                 "Vessel":"SRV","Count":0,"Inventory":[]}
                """);
        pipeline.journal(touchdown("2026-07-24T16:49:37Z"));
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:50:00Z","event":"Disembark",
                 "SRV":true,"ID":10,"StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155945939738,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "OnStation":false,"OnPlanet":true}
                """);
        if (sampling) {
            int index = 0;
            for (String scanType : List.of("Log", "Sample", "Analyse")) {
                pipeline.journal("""
                        {"timestamp":"2026-07-24T16:5%d:30Z",
                         "event":"ScanOrganic","ScanType":"%s",
                         "Genus":"$Codex_Ent_Bacterial_Genus_Name;",
                         "Genus_Localised":"Bacteria",
                         "Variant":"$Codex_Ent_Bacterial_01_F_Name;",
                         "Variant_Localised":"Bacterium Bullaris - Red",
                         "SystemAddress":23155945939738,"Body":20}
                        """.formatted(index++, scanType));
            }
        }
        pipeline.journal(embark("2026-07-24T16:54:00Z", 10));
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:55:00Z","event":"Liftoff",
                 "PlayerControlled":true,
                 "StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155945939738,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "OnStation":false,"OnPlanet":true}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-24T16:56:00Z","event":"DockSRV",
                 "ID":10,"SRVType":"lander01","SRVType_Localised":"Nomad"}
                """);
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

    private static String touchdown(String timestamp) {
        return """
                {"timestamp":"%s","event":"Touchdown",
                 "PlayerControlled":true,
                 "StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155945939738,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "OnStation":false,"OnPlanet":true}
                """.formatted(timestamp);
    }

    private static String embark(String timestamp, long vehicleId) {
        return """
                {"timestamp":"%s","event":"Embark","SRV":true,"ID":%d,
                 "StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155945939738,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "OnStation":false,"OnPlanet":true}
                """.formatted(timestamp, vehicleId);
    }

    // -------------------------------------------------------------- reading

    /** Canonical state as it stood when that record had just been applied. */
    private static CurrentGameStateSnapshot stateAfter(
            DecisionProductionPipeline pipeline,
            String journalEvent
    ) {
        return projectionOf(pipeline.capturedProjections(), journalEvent)
                .currentState();
    }

    /**
     * The request one turn would build from that record alone.
     *
     * <p>Every earlier trigger is drained through a turn of its own first, so
     * each request sees only what its own record changed — the same order the
     * runtime produces.</p>
     */
    private String turnFor(
            DecisionProductionPipeline pipeline,
            String journalEvent
    ) {
        List<ProjectedObservation> triggers = pipeline.capturedTriggers();
        ProjectedObservation wanted = projectionOf(triggers, journalEvent);
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

    private static ProjectedObservation projectionOf(
            List<ProjectedObservation> projections,
            String journalEvent
    ) {
        List<ProjectedObservation> matches = new ArrayList<>();
        for (ProjectedObservation projected : projections) {
            if (projected.trigger().payload()
                    instanceof JournalEventObservation event
                    && event.getClass().getSimpleName().equals(journalEvent)) {
                matches.add(projected);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException("no " + journalEvent + " projected");
        }
        return matches.getFirst();
    }
}
