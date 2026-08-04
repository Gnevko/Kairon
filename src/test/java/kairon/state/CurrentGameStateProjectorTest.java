package kairon.state;

import kairon.semantics.BodyIdentity;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class CurrentGameStateProjectorTest {

    @Test
    void projectsExistingIdentityLoadoutLocationAndBodySemantics() {
        Fixture fixture = new Fixture();

        fixture.apply("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Commander",
                 "FID":"F100","Name":"Cmdr Test"}
                """);
        CurrentGameStateSnapshot commander =
                fixture.projector.currentSnapshot();
        assertEquals("F100", commander.commanderFid());
        assertNull(commander.shipId());

        fixture.apply("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"LoadGame",
                 "FID":"F100","ShipID":9,"Ship":"krait_mkii",
                 "ShipName":"Caspian"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:00:02Z","event":"Loadout",
                 "ShipID":9,"Ship":"krait_mkii","ShipName":"Caspian",
                 "Modules":[
                   {"Slot":"MainEngines","Item":"int_engine_size6_class5"}
                 ]}
                """);
        CurrentGameStateSnapshot ship = fixture.projector.currentSnapshot();
        assertEquals(9L, ship.shipId());
        assertEquals("krait_mkii", ship.shipType());
        assertEquals("Caspian", ship.shipName());
        assertNotNull(ship.loadoutHash());
        assertTrue(ship.loadoutHash().startsWith("lo1-"));

        fixture.apply("""
                {"timestamp":"2026-07-30T10:00:03Z","event":"FSDJump",
                 "StarSystem":"Test System","SystemAddress":1001}
                """);
        CurrentGameStateSnapshot jump = fixture.projector.currentSnapshot();
        assertEquals(1001L, jump.systemAddress());
        assertEquals("Test System", jump.systemName());
        assertEquals(CommanderLocationMode.SHIP, jump.commanderMode());
        // A completed hyperspace jump arrives in supercruise.
        assertEquals(FlightMode.SUPERCRUISE, jump.flightMode());
        assertEquals(CurrentGameStateSnapshot.VEHICLE_SHIP,
                jump.vehicleKind());
        assertEquals(Boolean.FALSE, jump.activeOrganicSampling());

        fixture.apply("""
                {"timestamp":"2026-07-30T10:00:04Z","event":"Location",
                 "StarSystem":"Test System","SystemAddress":1001,
                 "Body":"Test System 2","BodyID":2,
                 "OnFoot":false,"InSRV":false,"Docked":true}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:00:05Z","event":"Scan",
                 "SystemAddress":1001,"BodyID":2,
                 "BodyName":"Test System 2","PlanetClass":"Rocky body",
                 "Landable":true,"WasDiscovered":true,"WasMapped":false,
                 "WasFootfalled":false,"DistanceFromArrivalLS":42.5}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:00:06Z",
                 "event":"SAASignalsFound","SystemAddress":1001,
                 "BodyName":"Test System 2","BodyID":2,
                 "Signals":[
                   {"Type":"$SAA_SignalType_Biological;","Count":4},
                   {"Type":"$SAA_SignalType_Geological;","Count":2}
                 ]}
                """);

        CurrentGameStateSnapshot body = fixture.projector.currentSnapshot();
        assertEquals(2L, body.bodyId());
        assertEquals("Test System 2", body.bodyName());
        assertEquals("Rocky body", body.bodyType());
        assertEquals(FlightMode.DOCKED, body.flightMode());
        assertEquals(4, body.biologicalSignalCount());
        assertEquals(2, body.geologicalSignalCount());
        assertEquals(Boolean.TRUE, body.landable());
        assertEquals(Boolean.TRUE, body.wasDiscovered());
        assertEquals(Boolean.FALSE, body.wasMapped());
        assertEquals(Boolean.FALSE, body.wasFootfalled());
        assertEquals(42.5, body.distanceFromArrivalLs());
        assertEquals(Boolean.TRUE, body.bodyHasBiology());

        fixture.apply("""
                {"timestamp":"2026-07-30T10:00:07Z",
                 "event":"ScanOrganic","ScanType":"Log",
                 "SystemAddress":1001,"Body":2}
                """);
        assertEquals(
                Boolean.TRUE,
                fixture.projector.currentSnapshot().activeOrganicSampling()
        );
        fixture.apply("""
                {"timestamp":"2026-07-30T10:00:08Z",
                 "event":"ScanOrganic","ScanType":"Analyse",
                 "SystemAddress":1001,"Body":2}
                """);
        assertEquals(
                Boolean.FALSE,
                fixture.projector.currentSnapshot().activeOrganicSampling()
        );
    }

    @Test
    void scanPlanetClassSurvivesBiologicalUpdate() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:15:00Z","event":"Scan",
                 "SystemAddress":1101,"BodyID":21,"BodyName":"Acheron",
                 "BodyType":"Planet","PlanetClass":"Icy body",
                 "Landable":true,"WasDiscovered":true,"WasMapped":false}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:15:01Z","event":"SAASignalsFound",
                 "SystemAddress":1101,"BodyName":"Acheron","BodyID":21,
                 "Signals":[
                   {"Type":"$SAA_SignalType_Biological;","Count":2},
                   {"Type":"$SAA_SignalType_Geological;","Count":1}
                 ]}
                """);

        BodyContext context = bodyContextFor(
                fixture.projector,
                1101L,
                21L
        );

        assertNotNull(context);
        assertEquals("Planet", context.bodyType());
        assertEquals("Icy body", context.planetClass());
        assertNull(context.starType());
        assertEquals("Acheron", context.bodyName());
        assertEquals(2, context.biologicalSignalCount());
        assertEquals(1, context.geologicalSignalCount());
    }

    @Test
    void scanStarTypeSurvivesBodyNameUpdate() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:16:00Z","event":"Scan",
                 "SystemAddress":1102,"BodyID":22,"BodyName":"Helios",
                 "StarType":"K","Landable":false}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:16:01Z","event":"Scan",
                 "SystemAddress":1102,"BodyID":22,
                 "BodyName":"Helios Prime","Landable":true}
                """);

        BodyContext context = bodyContextFor(
                fixture.projector,
                1102L,
                22L
        );

        assertNotNull(context);
        assertEquals("K", context.starType());
        assertEquals("Helios Prime", context.bodyName());
        assertEquals(Boolean.TRUE, context.landable());
    }

    @Test
    void broadAndDetailCoexistForSameBodyIdentity() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:17:00Z","event":"Scan",
                 "SystemAddress":1103,"BodyID":23,"BodyName":"Altair",
                 "BodyType":"Planet","PlanetClass":"Rocky body","StarType":"G"}
                """);

        BodyContext context = bodyContextFor(
                fixture.projector,
                1103L,
                23L
        );

        assertNotNull(context);
        assertEquals("Planet", context.bodyType());
        assertEquals("Rocky body", context.planetClass());
        assertEquals("G", context.starType());
    }

    @Test
    void independentBodyClassificationDimensionsDoNotOverwriteEachOther() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:18:00Z","event":"Scan",
                 "SystemAddress":1104,"BodyID":24,"BodyName":"Lacerta",
                 "BodyType":"Planet"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:18:01Z","event":"Scan",
                 "SystemAddress":1104,"BodyID":24,"BodyName":"Lacerta",
                 "PlanetClass":"Icy body"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:18:02Z","event":"Scan",
                 "SystemAddress":1104,"BodyID":24,"BodyName":"Lacerta",
                 "StarType":"K"}
                """);

        BodyContext context = bodyContextFor(
                fixture.projector,
                1104L,
                24L
        );

        assertNotNull(context);
        assertEquals("Planet", context.bodyType());
        assertEquals("Icy body", context.planetClass());
        assertEquals("K", context.starType());
    }

    @Test
    void canonicalBodySelectionPreservesDetailAcrossBroadUpdates() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:18:30Z","event":"FSDJump",
                 "StarSystem":"Selection","SystemAddress":1180}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:18:31Z","event":"Scan",
                 "SystemAddress":1180,"BodyID":80,"BodyName":"Kepler",
                 "PlanetClass":"Icy body"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:18:32Z","event":"ApproachBody",
                 "StarSystem":"Selection","SystemAddress":1180,
                 "Body":"Kepler","BodyID":80}
                """);
        CurrentGameStateSnapshot before = fixture.projector.currentSnapshot();
        assertNull(before.broadBodyType());
        assertEquals("Icy body", before.planetClass());

        fixture.apply("""
                {"timestamp":"2026-07-30T10:18:33Z","event":"SupercruiseExit",
                 "StarSystem":"Selection","SystemAddress":1180,
                 "Body":"Kepler","BodyID":80,"BodyType":"Planet"}
                """);
        CurrentGameStateSnapshot after = fixture.projector.currentSnapshot();

        assertEquals("Planet", after.broadBodyType());
        assertEquals("Icy body", after.planetClass());
        assertNull(after.starType());

        assertEquals("Icy body", before.planetClass());
        assertNull(before.starType());
    }

    @Test
    void starDetailAndBroadStarCoexistForCurrentBody() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:18:40Z","event":"FSDJump",
                 "StarSystem":"Sirius","SystemAddress":1181}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:18:41Z","event":"Scan",
                 "SystemAddress":1181,"BodyID":81,"BodyName":"Sirius System",
                 "StarType":"K"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:18:42Z","event":"ApproachBody",
                 "StarSystem":"Sirius","SystemAddress":1181,
                 "Body":"Sirius System","BodyID":81}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:18:43Z","event":"SupercruiseExit",
                 "StarSystem":"Sirius","SystemAddress":1181,
                 "Body":"Sirius System","BodyID":81,"BodyType":"Star"}
                """);

        CurrentGameStateSnapshot snapshot =
                fixture.projector.currentSnapshot();
        assertEquals("Star", snapshot.broadBodyType());
        assertEquals("K", snapshot.starType());
        assertNull(snapshot.planetClass());
    }

    @Test
    void broadBodyTypeOnlySelectionLeavesDetailNull() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:18:50Z","event":"FSDJump",
                 "StarSystem":"BroadOnly","SystemAddress":1182}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:18:51Z","event":"ApproachBody",
                 "StarSystem":"BroadOnly","SystemAddress":1182,
                 "Body":"Narrow","BodyID":82,"BodyType":"Planet"}
                """);

        CurrentGameStateSnapshot snapshot =
                fixture.projector.currentSnapshot();
        assertEquals("Planet", snapshot.broadBodyType());
        assertNull(snapshot.planetClass());
        assertNull(snapshot.starType());
    }

    @Test
    void registryDetailActivatesOnlyForExactCurrentBodyIdentity() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:19:00Z","event":"FSDJump",
                 "StarSystem":"ExactA","SystemAddress":1183}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:19:01Z","event":"Scan",
                 "SystemAddress":1183,"BodyID":83,"BodyName":"SharedID",
                 "BodyType":"Planet","PlanetClass":"Rocky body"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:19:02Z","event":"ApproachBody",
                 "StarSystem":"ExactA","SystemAddress":1183,
                 "Body":"Current","BodyID":84,"BodyType":"Planet"}
                """);
        CurrentGameStateSnapshot currentBeforeSelection =
                fixture.projector.currentSnapshot();
        assertEquals("Planet", currentBeforeSelection.broadBodyType());
        assertNull(currentBeforeSelection.planetClass());

        fixture.apply("""
                {"timestamp":"2026-07-30T10:19:03Z","event":"ApproachBody",
                 "StarSystem":"ExactA","SystemAddress":1183,
                 "Body":"SharedID","BodyID":83,"BodyType":"Planet"}
                """);
        CurrentGameStateSnapshot currentFromMatch =
                fixture.projector.currentSnapshot();
        assertEquals("Planet", currentFromMatch.broadBodyType());
        assertEquals("Rocky body", currentFromMatch.planetClass());
    }

    @Test
    void currentBodySelectionKeepsClassificationByExactKeyAcrossSwitches() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:00Z","event":"FSDJump",
                 "StarSystem":"Switch","SystemAddress":1190}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:01Z","event":"Scan",
                 "SystemAddress":1190,"BodyID":90,"BodyName":"Body A",
                 "BodyType":"Planet","PlanetClass":"Icy body"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:02Z","event":"Scan",
                 "SystemAddress":1190,"BodyID":91,"BodyName":"Body B",
                 "BodyType":"Star","StarType":"K"}
                """);

        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:03Z","event":"ApproachBody",
                 "StarSystem":"Switch","SystemAddress":1190,
                 "Body":"Body A","BodyID":90,"BodyType":"Planet"}
                """);
        CurrentGameStateSnapshot first = fixture.projector.currentSnapshot();
        assertEquals("Planet", first.broadBodyType());
        assertEquals("Icy body", first.planetClass());
        assertNull(first.starType());

        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:04Z","event":"ApproachBody",
                 "StarSystem":"Switch","SystemAddress":1190,
                 "Body":"Body B","BodyID":91,"BodyType":"Star"}
                """);
        CurrentGameStateSnapshot second = fixture.projector.currentSnapshot();
        assertEquals("Star", second.broadBodyType());
        assertNull(second.planetClass());
        assertEquals("K", second.starType());

        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:05Z","event":"ApproachBody",
                 "StarSystem":"Switch","SystemAddress":1190,
                 "Body":"Body A","BodyID":90,"BodyType":"Planet"}
                """);
        CurrentGameStateSnapshot third = fixture.projector.currentSnapshot();
        assertEquals("Planet", third.broadBodyType());
        assertEquals("Icy body", third.planetClass());
        assertNull(third.starType());
    }

    @Test
    void clearingCurrentBodyKeepsRegistryFactsAndNullsCurrentDimensions() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:10Z","event":"FSDJump",
                 "StarSystem":"Clear","SystemAddress":1191}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:11Z","event":"Scan",
                 "SystemAddress":1191,"BodyID":91,"BodyName":"Body C",
                 "BodyType":"Planet","PlanetClass":"Icy body","StarType":"K"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:12Z","event":"ApproachBody",
                 "StarSystem":"Clear","SystemAddress":1191,
                 "Body":"Body C","BodyID":91,"BodyType":"Planet"}
                """);

        CurrentGameStateSnapshot selectedBeforeClear =
                fixture.projector.currentSnapshot();
        assertEquals(91L, selectedBeforeClear.bodyId());
        assertEquals("Planet", selectedBeforeClear.broadBodyType());
        assertEquals("Icy body", selectedBeforeClear.planetClass());
        assertEquals("K", selectedBeforeClear.starType());

        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:13Z","event":"SupercruiseEntry",
                 "StarSystem":"Clear","SystemAddress":1191}
                """);
        CurrentGameStateSnapshot cleared = fixture.projector.currentSnapshot();
        assertNull(cleared.bodyId());
        assertNull(cleared.broadBodyType());
        assertNull(cleared.planetClass());
        assertNull(cleared.starType());

        BodyContext cached = bodyContextFor(
                fixture.projector,
                1191L,
                91L
        );
        assertNotNull(cached);
        assertEquals("Icy body", cached.planetClass());
        assertEquals("K", cached.starType());

        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:14Z","event":"ApproachBody",
                 "StarSystem":"Clear","SystemAddress":1191,
                 "Body":"Body C","BodyID":91,"BodyType":"Planet"}
                """);
        CurrentGameStateSnapshot restored = fixture.projector.currentSnapshot();
        assertEquals(91L, restored.bodyId());
        assertEquals("Planet", restored.broadBodyType());
        assertEquals("Icy body", restored.planetClass());
        assertEquals("K", restored.starType());
    }

    @Test
    void canonicalBodyDimensionsChangeIndependently() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:20Z","event":"FSDJump",
                 "StarSystem":"Invariant","SystemAddress":1192}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:21Z","event":"ApproachBody",
                 "StarSystem":"Invariant","SystemAddress":1192,
                 "Body":"Invariant Body","BodyID":92,"BodyType":"Planet"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:22Z","event":"Scan",
                 "SystemAddress":1192,"BodyID":92,"PlanetClass":"Icy body"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:23Z","event":"Scan",
                 "SystemAddress":1192,"BodyID":92,"StarType":"K"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:24Z","event":"SupercruiseExit",
                 "StarSystem":"Invariant","SystemAddress":1192,
                 "Body":"Invariant Body","BodyID":92,"BodyType":"Star"}
                """);

        CurrentGameStateSnapshot snapshot =
                fixture.projector.currentSnapshot();
        assertEquals("Star", snapshot.broadBodyType());
        assertEquals("Icy body", snapshot.planetClass());
        assertEquals("K", snapshot.starType());

        assertEquals("Icy body", snapshot.planetClass());
        assertEquals("K", snapshot.starType());
    }

    @Test
    void bodyChangeSetReflectsEachBodyDimensionIndependently() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:30Z","event":"FSDJump",
                 "StarSystem":"ChangeSet","SystemAddress":1193}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:31Z","event":"ApproachBody",
                 "StarSystem":"ChangeSet","SystemAddress":1193,
                 "Body":"Observed Body","BodyID":93,"BodyType":"Planet"}
                """);
        CurrentGameStateSnapshot broadPlanet =
                fixture.projector.currentSnapshot();

        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:32Z","event":"Scan",
                 "SystemAddress":1193,"BodyID":93,"PlanetClass":"Icy body"}
                """);
        CurrentGameStateSnapshot planetClassOnly =
                fixture.projector.currentSnapshot();
        assertTrue(CurrentGameStateChangeSet.between(
                broadPlanet,
                planetClassOnly
        ).changedFacets().contains(GameStateFacet.BODY));

        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:33Z","event":"Scan",
                 "SystemAddress":1193,"BodyID":93,"StarType":"K"}
                """);
        CurrentGameStateSnapshot starTypeOnly =
                fixture.projector.currentSnapshot();
        assertTrue(CurrentGameStateChangeSet.between(
                planetClassOnly,
                starTypeOnly
        ).changedFacets().contains(GameStateFacet.BODY));

        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:34Z","event":"SupercruiseExit",
                 "StarSystem":"ChangeSet","SystemAddress":1193,
                 "Body":"Observed Body","BodyID":93,"BodyType":"Star"}
                """);
        CurrentGameStateSnapshot broadStar =
                fixture.projector.currentSnapshot();
        assertTrue(CurrentGameStateChangeSet.between(
                starTypeOnly,
                broadStar
        ).changedFacets().contains(GameStateFacet.BODY));

        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:35Z","event":"SupercruiseExit",
                 "StarSystem":"ChangeSet","SystemAddress":1193,
                 "Body":"Observed Body","BodyID":93,"BodyType":"Star"}
                """);
        CurrentGameStateSnapshot repeated =
                fixture.projector.currentSnapshot();
        assertFalse(CurrentGameStateChangeSet.between(
                broadStar,
                repeated
        ).changedFacets().contains(GameStateFacet.BODY));
    }

    @Test
    void bodyTypeCompatibilityAccessorKeepsPreMigrationBehavior() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:40Z","event":"FSDJump",
                 "StarSystem":"Compat","SystemAddress":1194}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:41Z","event":"Scan",
                 "SystemAddress":1194,"BodyID":94,"BodyName":"Compat Body",
                 "PlanetClass":"Icy body"}
                """);

        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:42Z","event":"ApproachBody",
                 "StarSystem":"Compat","SystemAddress":1194,
                 "Body":"Compat Body","BodyID":94}
                """);
        CurrentGameStateSnapshot snapshot =
                fixture.projector.currentSnapshot();
        assertEquals("Icy body", snapshot.bodyType());
        assertNull(snapshot.broadBodyType());
    }

    @Test
    void missingSourceValueDoesNotClearBodyRegistryClassification() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:19:00Z","event":"Scan",
                 "SystemAddress":1105,"BodyID":25,"BodyName":"Vega",
                 "BodyType":"Planet","PlanetClass":"Icy body","StarType":"K"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:19:01Z","event":"Scan",
                 "SystemAddress":1105,"BodyID":25,"BodyName":"Vega Prime",
                 "Landable":true,"WasDiscovered":true}
                """);

        BodyContext context = bodyContextFor(
                fixture.projector,
                1105L,
                25L
        );

        assertNotNull(context);
        assertEquals("Planet", context.bodyType());
        assertEquals("Icy body", context.planetClass());
        assertEquals("K", context.starType());
        assertEquals("Vega Prime", context.bodyName());
    }

    @Test
    void bodyRegistryKeysAreIsolated() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:00Z","event":"Scan",
                 "SystemAddress":1106,"BodyID":26,"BodyName":"Mizar",
                 "BodyType":"Planet","PlanetClass":"Rocky body","StarType":"G"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:20:01Z","event":"Scan",
                 "SystemAddress":1107,"BodyID":27,"BodyName":"Lupus",
                 "BodyType":"Star","PlanetClass":"Icy body","StarType":"K"}
                """);

        BodyContext first = bodyContextFor(fixture.projector, 1106L, 26L);
        BodyContext second = bodyContextFor(fixture.projector, 1107L, 27L);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals("Mizar", first.bodyName());
        assertEquals("Lupus", second.bodyName());
        assertEquals("Planet", first.bodyType());
        assertEquals("Star", second.bodyType());
        assertEquals("Rocky body", first.planetClass());
        assertEquals("Icy body", second.planetClass());
        assertEquals("G", first.starType());
        assertEquals("K", second.starType());
    }

    @Test
    void bodyContextImmutabilityAfterMergeUpdate() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:21:00Z","event":"Scan",
                 "SystemAddress":1108,"BodyID":28,"BodyName":"Sirius",
                 "BodyType":"Planet","PlanetClass":"Rocky body"}
                """);

        BodyContext before = bodyContextFor(fixture.projector, 1108L, 28L);
        assertNotNull(before);

        fixture.apply("""
                {"timestamp":"2026-07-30T10:21:01Z","event":"SAASignalsFound",
                 "SystemAddress":1108,"BodyName":"Sirius","BodyID":28,
                 "Signals":[
                   {"Type":"$SAA_SignalType_Biological;","Count":4}
                 ]}
                """);

        BodyContext after = bodyContextFor(fixture.projector, 1108L, 28L);
        assertNotNull(after);
        assertNotSame(before, after);
        assertEquals("Planet", before.bodyType());
        assertEquals("Rocky body", before.planetClass());
        assertEquals("Planet", after.bodyType());
        assertEquals("Rocky body", after.planetClass());
        assertEquals(4, after.biologicalSignalCount());
    }

    @Test
    void preservesExistingPresenceAndVehicleTransitions() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T11:00:00Z","event":"FSDJump",
                 "StarSystem":"Vehicles","SystemAddress":2001}
                """);

        fixture.apply("""
                {"timestamp":"2026-07-30T11:00:00Z",
                 "event":"LaunchFighter","ID":77,
                 "PlayerControlled":true}
                """);
        CurrentGameStateSnapshot fighter =
                fixture.projector.currentSnapshot();
        assertEquals(CommanderLocationMode.SHIP, fighter.commanderMode());
        assertEquals(CurrentGameStateSnapshot.VEHICLE_UNKNOWN,
                fighter.vehicleKind());
        assertEquals(77L, fighter.activeVehicleId());

        fixture.apply("""
                {"timestamp":"2026-07-30T11:00:01Z","event":"LaunchSRV",
                 "ID":101,"SRVType":"lander01",
                 "SRVType_Localised":"Nomad","PlayerControlled":true}
                """);
        CurrentGameStateSnapshot launched =
                fixture.projector.currentSnapshot();
        assertEquals(
                CommanderLocationMode.SLV,
                launched.commanderMode(),
                "a Nomad is a Ship-Launched Vessel, not an SRV"
        );
        assertEquals(CurrentGameStateSnapshot.VEHICLE_SLV,
                launched.vehicleKind());
        assertEquals(101L, launched.activeVehicleId());

        fixture.apply("""
                {"timestamp":"2026-07-30T11:00:02Z","event":"Disembark",
                 "SRV":true,"ID":101,"Body":"Vehicles 1","BodyID":1}
                """);
        assertEquals(
                CommanderLocationMode.ON_FOOT,
                fixture.projector.currentSnapshot().commanderMode()
        );

        fixture.apply("""
                {"timestamp":"2026-07-30T11:00:03Z","event":"Embark",
                 "SRV":true,"ID":101,"Body":"Vehicles 1","BodyID":1}
                """);
        CurrentGameStateSnapshot embarked =
                fixture.projector.currentSnapshot();
        assertEquals(
                CommanderLocationMode.SLV,
                embarked.commanderMode(),
                "SRV=true is the record's form, not the vessel's class"
        );
        assertEquals(CurrentGameStateSnapshot.VEHICLE_SLV,
                embarked.vehicleKind());

        fixture.apply("""
                {"timestamp":"2026-07-30T11:00:04Z","event":"DockSRV",
                 "ID":101,"SRVType":"lander01",
                 "SRVType_Localised":"Nomad"}
                """);
        CurrentGameStateSnapshot dockedSrv =
                fixture.projector.currentSnapshot();
        assertEquals(CommanderLocationMode.SHIP, dockedSrv.commanderMode());
        assertEquals(CurrentGameStateSnapshot.VEHICLE_SHIP,
                dockedSrv.vehicleKind());
        assertNull(dockedSrv.activeVehicleId());

        fixture.apply("""
                {"timestamp":"2026-07-30T11:00:05Z","event":"Disembark",
                 "SRV":false,"Body":"Vehicles 1","BodyID":1}
                """);
        assertEquals(
                CommanderLocationMode.ON_FOOT,
                fixture.projector.currentSnapshot().commanderMode()
        );
        fixture.apply("""
                {"timestamp":"2026-07-30T11:00:06Z","event":"Embark",
                 "SRV":false,"Body":"Vehicles 1","BodyID":1}
                """);
        assertEquals(
                CommanderLocationMode.SHIP,
                fixture.projector.currentSnapshot().commanderMode()
        );
    }

    /**
     * Two ambiguous records that are unambiguous together.
     *
     * <p>The journal tags a cargo snapshot with the vessel whose hold it
     * describes. After a {@code LaunchFighter} that established no type, that
     * tag is the first thing that says anything about the vehicle out there —
     * and what the pair says is a Ship-Launched Vessel: launched through the
     * fighter channel, held through the SRV one, which is the Nomad's lifecycle
     * and not a conventional SRV's. It is still only that. Where the Commander
     * is sitting is a different fact, and an inventory event does not establish
     * it.</p>
     */
    @Test
    void anAmbiguousLaunchAndAnSrvHoldTogetherMeanAShipLaunchedVessel() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-24T16:48:45Z","event":"LaunchFighter",
                 "Loadout":"base","ID":10,"PlayerControlled":true}
                """);
        CurrentGameStateSnapshot launched =
                fixture.projector.currentSnapshot();
        assertEquals(
                CurrentGameStateSnapshot.VEHICLE_UNKNOWN,
                launched.vehicleKind()
        );
        assertEquals(10L, launched.activeVehicleId());

        fixture.apply("""
                {"timestamp":"2026-07-24T16:48:51Z","event":"Cargo",
                 "Vessel":"SRV","Count":0,"Inventory":[]}
                """);
        CurrentGameStateSnapshot narrowed =
                fixture.projector.currentSnapshot();

        assertEquals(
                CurrentGameStateSnapshot.VEHICLE_SLV,
                narrowed.vehicleKind()
        );
        assertEquals(
                10L,
                narrowed.activeVehicleId(),
                "the runtime identity is not touched"
        );
        assertEquals(
                launched.commanderMode(),
                narrowed.commanderMode(),
                "whose hold this is says nothing about who is sitting in it"
        );
        assertEquals(
                launched.flightMode(),
                narrowed.flightMode()
        );
    }

    /** An empty hold is still a hold, and still says whose it is. */
    @Test
    void anEmptyCargoHoldIsStillEvidence() {
        Fixture fixture = srvOfUnknownKind();
        fixture.apply("""
                {"timestamp":"2026-07-24T16:48:51Z","event":"Cargo",
                 "Vessel":"SRV","Count":0,"Inventory":[]}
                """);

        assertEquals(
                CurrentGameStateSnapshot.VEHICLE_SLV,
                fixture.projector.currentSnapshot().vehicleKind()
        );
    }

    /** Casing is the journal's business, not the contract's. */
    @Test
    void theVesselTagIsMatchedWithoutRegardToCase() {
        for (String written : new String[]{"SRV", "srv", "Srv", " sRv "}) {
            Fixture fixture = srvOfUnknownKind();
            fixture.apply("""
                    {"timestamp":"2026-07-24T16:48:51Z","event":"Cargo",
                     "Vessel":"%s","Count":0}
                    """.formatted(written));

            assertEquals(
                    CurrentGameStateSnapshot.VEHICLE_SLV,
                    fixture.projector.currentSnapshot().vehicleKind(),
                    written + " must be recognised"
            );
        }
    }

    /**
     * The same hold tag, with no ambiguous launch behind it, still means SRV.
     *
     * <p>The composite rule is about a pair of observations, not about the tag.
     * Where there is no vehicle out that was launched without naming itself,
     * the tag is read exactly as it was before Ship-Launched Vessels existed —
     * otherwise every stray inventory snapshot would promote something to the
     * rarer class.</p>
     */
    @Test
    void anSrvHoldWithNoAmbiguousLaunchIsStillAnSrv() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-24T16:48:51Z","event":"Cargo",
                 "Vessel":"SRV","Count":0}
                """);

        CurrentGameStateSnapshot state = fixture.projector.currentSnapshot();
        assertEquals(
                CurrentGameStateSnapshot.VEHICLE_SRV,
                state.vehicleKind()
        );
        assertNull(
                state.activeVehicleId(),
                "no launch happened, so nothing is out"
        );
    }

    /** A launch that names nothing, left alone, establishes nothing. */
    @Test
    void anAmbiguousLaunchWithNoFurtherEvidenceStaysUnknown() {
        Fixture fixture = srvOfUnknownKind();

        CurrentGameStateSnapshot state = fixture.projector.currentSnapshot();
        assertEquals(
                CurrentGameStateSnapshot.VEHICLE_UNKNOWN,
                state.vehicleKind()
        );
        assertEquals(10L, state.activeVehicleId());
        assertEquals(
                CommanderLocationMode.UNKNOWN,
                state.commanderMode(),
                "a launch that establishes no class moves nobody: presence is "
                        + "left exactly as this fixture had it"
        );
    }

    /** No tag is no evidence, not evidence of something else. */
    @Test
    void aCargoSnapshotWithNoVesselChangesNothing() {
        Fixture fixture = srvOfUnknownKind();
        fixture.apply("""
                {"timestamp":"2026-07-24T16:48:51Z","event":"Cargo",
                 "Count":0,"Inventory":[]}
                """);

        CurrentGameStateSnapshot state = fixture.projector.currentSnapshot();
        assertEquals(
                CurrentGameStateSnapshot.VEHICLE_UNKNOWN,
                state.vehicleKind()
        );
        assertEquals(10L, state.activeVehicleId());
    }

    /**
     * Supporting evidence never overwrites a stronger fact.
     *
     * <p>A launch established a Nomad outright. A cargo snapshot arriving after
     * it is not a vehicle switch, and letting an inventory event rewrite what a
     * deployment event said would lose the more specific of the two.</p>
     */
    @Test
    void aCargoSnapshotDoesNotOverwriteAKnownVehicleKind() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-24T16:48:45Z","event":"LaunchSRV",
                 "ID":10,"SRVType":"lander01","SRVType_Localised":"Nomad",
                 "PlayerControlled":true}
                """);
        assertEquals(
                CurrentGameStateSnapshot.VEHICLE_SLV,
                fixture.projector.currentSnapshot().vehicleKind()
        );

        fixture.apply("""
                {"timestamp":"2026-07-24T16:48:51Z","event":"Cargo",
                 "Vessel":"SRV","Count":0}
                """);

        assertEquals(
                CurrentGameStateSnapshot.VEHICLE_SLV,
                fixture.projector.currentSnapshot().vehicleKind(),
                "an established class survives the weaker evidence"
        );
    }

    /** A ship's hold is not a claim that the vehicle was put away. */
    @Test
    void aShipCargoSnapshotDoesNotClearAnActiveSrv() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-24T16:48:45Z","event":"LaunchFighter",
                 "Loadout":"base","ID":10,"PlayerControlled":true}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-24T16:48:51Z","event":"Cargo",
                 "Vessel":"SRV","Count":0}
                """);
        assertEquals(
                CurrentGameStateSnapshot.VEHICLE_SLV,
                fixture.projector.currentSnapshot().vehicleKind()
        );

        fixture.apply("""
                {"timestamp":"2026-07-24T16:49:00Z","event":"Cargo",
                 "Vessel":"Ship","Count":4}
                """);

        CurrentGameStateSnapshot state = fixture.projector.currentSnapshot();
        assertEquals(
                CurrentGameStateSnapshot.VEHICLE_SLV,
                state.vehicleKind(),
                "recovering the vehicle is what DockSRV reports, not this"
        );
        assertEquals(10L, state.activeVehicleId());
    }

    /** A ship's hold on its own establishes no auxiliary vehicle. */
    @Test
    void aShipCargoSnapshotEstablishesNoVehicle() {
        Fixture fixture = srvOfUnknownKind();
        fixture.apply("""
                {"timestamp":"2026-07-24T16:48:51Z","event":"Cargo",
                 "Vessel":"Ship","Count":4}
                """);

        assertEquals(
                CurrentGameStateSnapshot.VEHICLE_UNKNOWN,
                fixture.projector.currentSnapshot().vehicleKind()
        );
    }

    /** A deployment that established no type, with runtime id 10 out. */
    private Fixture srvOfUnknownKind() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-24T16:48:45Z","event":"LaunchFighter",
                 "Loadout":"base","ID":10,"PlayerControlled":true}
                """);
        return fixture;
    }

    @Test
    void preservesExistingFlightModeTransitions() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T12:00:00Z","event":"FSDJump",
                 "StarSystem":"Flight","SystemAddress":3001}
                """);

        fixture.apply("""
                {"timestamp":"2026-07-30T12:00:01Z",
                 "event":"SupercruiseEntry","StarSystem":"Flight",
                 "SystemAddress":3001}
                """);
        assertEquals(
                FlightMode.SUPERCRUISE,
                fixture.projector.currentSnapshot().flightMode()
        );
        fixture.apply("""
                {"timestamp":"2026-07-30T12:00:02Z",
                 "event":"SupercruiseExit","StarSystem":"Flight",
                 "SystemAddress":3001,"Body":"Flight 3","BodyID":3}
                """);
        assertEquals(
                FlightMode.NORMAL_SPACE,
                fixture.projector.currentSnapshot().flightMode()
        );
        fixture.apply("""
                {"timestamp":"2026-07-30T12:00:03Z","event":"Touchdown",
                 "Body":"Flight 3","BodyID":3}
                """);
        assertEquals(
                FlightMode.LANDED,
                fixture.projector.currentSnapshot().flightMode()
        );
        fixture.apply("""
                {"timestamp":"2026-07-30T12:00:04Z","event":"Liftoff",
                 "Body":"Flight 3","BodyID":3}
                """);
        assertEquals(
                FlightMode.NORMAL_SPACE,
                fixture.projector.currentSnapshot().flightMode()
        );
        fixture.apply("""
                {"timestamp":"2026-07-30T12:00:05Z","event":"Docked",
                 "StarSystem":"Flight","SystemAddress":3001,
                 "StationName":"Test Station"}
                """);
        assertEquals(
                FlightMode.DOCKED,
                fixture.projector.currentSnapshot().flightMode()
        );
        fixture.apply("""
                {"timestamp":"2026-07-30T12:00:06Z","event":"Undocked",
                 "StationName":"Test Station"}
                """);
        assertEquals(
                FlightMode.NORMAL_SPACE,
                fixture.projector.currentSnapshot().flightMode()
        );
        fixture.apply("""
                {"timestamp":"2026-07-30T12:00:07Z","event":"StartJump",
                 "JumpType":"Hyperspace"}
                """);
        assertEquals(
                FlightMode.HYPERSPACE,
                fixture.projector.currentSnapshot().flightMode()
        );
        fixture.apply("""
                {"timestamp":"2026-07-30T12:00:08Z","event":"FSDJump",
                 "StarSystem":"Flight Next","SystemAddress":3002}
                """);
        assertEquals(
                FlightMode.SUPERCRUISE,
                fixture.projector.currentSnapshot().flightMode(),
                "the jump ends in supercruise at the arrival star"
        );
        fixture.apply("""
                {"timestamp":"2026-07-30T12:00:09Z","event":"LeaveBody",
                 "StarSystem":"Flight Next","SystemAddress":3002,
                 "Body":"Flight Next 1","BodyID":1}
                """);
        CurrentGameStateSnapshot leaveBody =
                fixture.projector.currentSnapshot();
        assertEquals(FlightMode.SUPERCRUISE, leaveBody.flightMode());
        assertNull(leaveBody.bodyId());
    }

    @Test
    void snapshotsHaveValueSemanticsAndDoNotExposeMutableCollections() {
        Fixture fixture = identifiedFixture();
        CurrentGameStateSnapshot before =
                fixture.projector.currentSnapshot();
        CurrentGameStateSnapshot equalCopy =
                fixture.projector.currentSnapshot();

        assertEquals(before, equalCopy);
        assertTrue(Arrays.stream(
                        CurrentGameStateSnapshot.class.getRecordComponents())
                .map(component -> component.getType())
                .noneMatch(type -> Collection.class.isAssignableFrom(type)
                        || Map.class.isAssignableFrom(type)
                        || type.isArray()));

        fixture.apply("""
                {"timestamp":"2026-07-30T13:00:00Z","event":"FSDJump",
                 "StarSystem":"Immutable","SystemAddress":4001}
                """);

        assertNull(before.systemAddress());
        assertEquals(4001L,
                fixture.projector.currentSnapshot().systemAddress());
    }

    @Test
    void preservesExistingShipAndCommanderResetScopes() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T13:10:00Z","event":"FSDJump",
                 "StarSystem":"Retained","SystemAddress":4101}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T13:10:01Z","event":"LaunchSRV",
                 "ID":88,"SRVType":"testbuggy",
                 "PlayerControlled":true}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T13:10:02Z","event":"LoadGame",
                 "FID":"F100","ShipID":14,"Ship":"sidewinder",
                 "ShipName":"Second"}
                """);

        CurrentGameStateSnapshot newShip =
                fixture.projector.currentSnapshot();
        assertEquals(14L, newShip.shipId());
        assertEquals("Retained", newShip.systemName());
        assertEquals(4101L, newShip.systemAddress());
        assertEquals(CommanderLocationMode.UNKNOWN, newShip.commanderMode());
        assertEquals(CurrentGameStateSnapshot.VEHICLE_UNKNOWN,
                newShip.vehicleKind());
        assertNull(newShip.activeVehicleId());
        assertNull(newShip.loadoutHash());

        fixture.apply("""
                {"timestamp":"2026-07-30T13:10:03Z","event":"Commander",
                 "FID":"F200","Name":"Other Commander"}
                """);
        CurrentGameStateSnapshot newCommander =
                fixture.projector.currentSnapshot();
        assertEquals("F200", newCommander.commanderFid());
        assertNull(newCommander.shipId());
        assertNull(newCommander.systemAddress());
        assertNull(newCommander.systemName());
        assertNull(newCommander.bodyId());
        assertEquals(CommanderLocationMode.UNKNOWN,
                newCommander.commanderMode());
        assertEquals(FlightMode.UNKNOWN, newCommander.flightMode());
    }

    @Test
    void factSpecificSnapshotDoesNotChangeCurrentBodySelection() {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T13:20:00Z","event":"FSDJump",
                 "StarSystem":"Remote Facts","SystemAddress":4201}
                """);
        PublishedObservation<JournalEventObservation> signals =
                fixture.apply("""
                        {"timestamp":"2026-07-30T13:20:01Z",
                         "event":"SAASignalsFound",
                         "SystemAddress":4201,
                         "BodyName":"Remote Facts 5","BodyID":5,
                         "Signals":[
                           {"Type":"$SAA_SignalType_Biological;",
                            "Count":5},
                           {"Type":"$SAA_SignalType_Geological;",
                            "Count":3}
                         ]}
                        """);

        assertNull(fixture.projector.currentSnapshot().bodyId());
        CurrentGameStateSnapshot fact =
                fixture.lastProjection.observationContext();
        assertEquals(4201L, fact.systemAddress());
        assertEquals(5L, fact.bodyId());
        assertEquals("Remote Facts 5", fact.bodyName());
        assertEquals(5, fact.biologicalSignalCount());
        assertEquals(3, fact.geologicalSignalCount());
        assertNull(fixture.projector.currentSnapshot().bodyId());
    }

    @Test
    void completedApplyPublishesOneWholeSnapshotToAnotherThread()
            throws Exception {
        Fixture fixture = identifiedFixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Location",
                 "StarSystem":"Visible","SystemAddress":5001,
                 "Body":"Visible 4","BodyID":4,
                 "OnFoot":true,"Docked":false}
                """);

        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            CurrentGameStateView view = fixture.projector;
            Future<CurrentGameStateSnapshot> result =
                    reader.submit(view::currentSnapshot);
            CurrentGameStateSnapshot observed = result.get();

            assertEquals("F100", observed.commanderFid());
            assertEquals(9L, observed.shipId());
            assertEquals(5001L, observed.systemAddress());
            assertEquals("Visible", observed.systemName());
            assertEquals(4L, observed.bodyId());
            assertEquals("Visible 4", observed.bodyName());
            assertEquals(CommanderLocationMode.ON_FOOT,
                    observed.commanderMode());
            assertEquals(FlightMode.NORMAL_SPACE, observed.flightMode());
        } finally {
            reader.shutdownNow();
        }
    }

    @Test
    void applyAndCaptureAtomicallyDescribesActualStateDifferences() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T15:00:00Z","event":"LoadGame",
                 "FID":"F100","ShipID":9,"Ship":"krait_mkii",
                 "ShipName":"Caspian"}
                """);
        CurrentGameStateProjection identity = fixture.lastProjection;

        assertEquals(CurrentGameStateSnapshot.unknown(),
                identity.previousState());
        assertEquals(
                Set.of(GameStateFacet.COMMANDER, GameStateFacet.SHIP),
                identity.changes().changedFacets()
        );
        assertTrue(identity.changes().changed());

        fixture.apply("""
                {"timestamp":"2026-07-30T15:00:01Z","event":"FSDJump",
                 "StarSystem":"Delta","SystemAddress":6001}
                """);
        CurrentGameStateProjection jump = fixture.lastProjection;
        CurrentGameStateSnapshot jumpSnapshot = jump.currentState();

        assertEquals(identity.currentState(), jump.previousState());
        assertEquals(
                Set.of(
                        GameStateFacet.SYSTEM,
                        GameStateFacet.PRESENCE,
                        GameStateFacet.FLIGHT,
                        GameStateFacet.VEHICLE,
                        GameStateFacet.BIOLOGICAL
                ),
                jump.changes().changedFacets()
        );

        fixture.apply("""
                {"timestamp":"2026-07-30T15:00:02Z",
                 "event":"SupercruiseEntry","StarSystem":"Delta",
                 "SystemAddress":6001}
                """);
        CurrentGameStateProjection supercruise = fixture.lastProjection;
        assertTrue(
                supercruise.changes().changedFacets().isEmpty(),
                "the jump already arrived in supercruise"
        );

        fixture.apply("""
                {"timestamp":"2026-07-30T15:00:03Z",
                 "event":"SupercruiseEntry","StarSystem":"Delta",
                 "SystemAddress":6001}
                """);
        CurrentGameStateProjection repeated = fixture.lastProjection;
        assertEquals(supercruise.currentState(), repeated.previousState());
        assertEquals(supercruise.currentState(), repeated.currentState());
        assertTrue(repeated.changes().changedFacets().isEmpty());
        assertFalse(repeated.changes().changed());

        fixture.apply("""
                {"timestamp":"2026-07-30T15:00:04Z","event":"Friends",
                 "Status":"Online","Name":"Friend"}
                """);
        CurrentGameStateProjection friends = fixture.lastProjection;
        assertEquals(repeated.currentState(), friends.previousState());
        assertEquals(repeated.currentState(), friends.currentState());
        assertTrue(friends.changes().changedFacets().isEmpty());

        fixture.apply("""
                {"timestamp":"2026-07-30T15:00:05Z",
                 "event":"Commander","FID":"F200",
                 "Name":"Other Commander"}
                """);
        CurrentGameStateProjection commanderSwitch =
                fixture.lastProjection;
        assertEquals(friends.currentState(),
                commanderSwitch.previousState());
        assertEquals(
                Set.of(
                        GameStateFacet.COMMANDER,
                        GameStateFacet.SHIP,
                        GameStateFacet.SYSTEM,
                        GameStateFacet.PRESENCE,
                        GameStateFacet.FLIGHT,
                        GameStateFacet.VEHICLE,
                        GameStateFacet.BIOLOGICAL
                ),
                commanderSwitch.changes().changedFacets()
        );
        assertNull(commanderSwitch.currentState().shipId());
        assertNull(commanderSwitch.currentState().systemAddress());

        assertEquals(FlightMode.SUPERCRUISE, jumpSnapshot.flightMode());
        assertEquals("Delta", jumpSnapshot.systemName());
        assertThrows(
                UnsupportedOperationException.class,
                () -> jump.changes()
                        .changedFacets()
                        .add(GameStateFacet.BODY)
        );
    }

    private static Fixture identifiedFixture() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T09:00:00Z","event":"LoadGame",
                 "FID":"F100","ShipID":9,"Ship":"krait_mkii",
                 "ShipName":"Caspian"}
                """);
        return fixture;
    }

    private static BodyContext bodyContextFor(
            CurrentGameStateProjector projector,
            long systemAddress,
            long bodyId
    ) {
        Map<BodyIdentity, BodyContext> registry = bodyRegistry(projector);
        return registry.get(new BodyIdentity(systemAddress, bodyId));
    }

    @SuppressWarnings("unchecked")
    private static Map<BodyIdentity, BodyContext> bodyRegistry(
            CurrentGameStateProjector projector
    ) {
        try {
            Field bodiesField = CurrentGameStateProjector.class.getDeclaredField(
                    "bodies"
            );
            bodiesField.setAccessible(true);
            return (Map<BodyIdentity, BodyContext>) bodiesField.get(projector);
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Cannot access internal body registry for test"
            );
        }
    }

    private static final class Fixture {

        private static final ObservationSource SOURCE =
                new ObservationSource("elite-journal", "state-test");
        private final JournalLineParser parser = new JournalLineParser();
        private final JournalObservationAdapter adapter =
                new JournalObservationAdapter(SOURCE);
        private final CurrentGameStateProjector projector =
                new CurrentGameStateProjector();
        private CurrentGameStateProjection lastProjection;
        private long sourceOffset;
        private long busSequence;

        private PublishedObservation<JournalEventObservation> apply(
                String rawJson
        ) {
            byte[] bytes = rawJson.strip().getBytes(StandardCharsets.UTF_8);
            ParsedJournalRecord parsed = assertInstanceOf(
                    ParsedJournalRecord.class,
                    parser.parse(new CompleteJournalRecord(
                            "Journal.state-test.log",
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
            PublishedObservation<JournalEventObservation> observation =
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
            lastProjection = projector.applyAndCapture(observation);
            return observation;
        }
    }
}
