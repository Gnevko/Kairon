package kairon.state;

import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.semantics.SemanticChangeKind;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticSourceRole;
import kairon.semantics.SemanticStateChange;
import kairon.semantics.SemanticSubject;
import kairon.semantics.SemanticValue;
import kairon.semantics.SemanticValueOrigin;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact field-level delta produced inside the projection boundary.
 *
 * <p>The decisive case is {@code ACTIVATED_FROM_CONTEXT}: a body fact that
 * reappears from the projector's stored registry must never read as newly
 * learned, and that can only be decided by write path, never by value.</p>
 */
class CurrentGameStateSemanticDeltaTest {

    @Test
    void unknownToKnownIsEstablished() {
        Fixture fixture = new Fixture();
        List<SemanticStateChange> changes = fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Commander",\
                "FID":"F900","Name":"Vex"}
                """);

        SemanticStateChange commander =
                change(changes, SemanticField.COMMANDER_FID);
        assertEquals(SemanticChangeKind.ESTABLISHED, commander.changeKind());
        assertFalse(commander.before().known());
        assertEquals(
                new SemanticValue.TextValue("F900"),
                commander.after()
        );
        assertEquals(
                SemanticValueOrigin.OBSERVATION,
                commander.origin()
        );
        assertEquals(SemanticSubject.COMMANDER, commander.subject());
    }

    @Test
    void knownToDifferentKnownIsUpdated() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"FSDJump",\
                "StarSystem":"Alpha","SystemAddress":11}
                """);
        List<SemanticStateChange> changes = fixture.apply("""
                {"timestamp":"2026-07-30T14:01:00Z","event":"FSDJump",\
                "StarSystem":"Beta","SystemAddress":12}
                """);

        SemanticStateChange system =
                change(changes, SemanticField.SYSTEM_NAME);
        assertEquals(SemanticChangeKind.UPDATED, system.changeKind());
        assertEquals(new SemanticValue.TextValue("Alpha"), system.before());
        assertEquals(new SemanticValue.TextValue("Beta"), system.after());
    }

    @Test
    void knownToClearedIsCleared() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 1",\
                "BodyID":3}
                """);
        List<SemanticStateChange> changes = fixture.apply("""
                {"timestamp":"2026-07-30T14:01:00Z",\
                "event":"SupercruiseEntry","StarSystem":"Alpha",\
                "SystemAddress":11}
                """);

        SemanticStateChange bodyId = change(changes, SemanticField.BODY_ID);
        assertEquals(SemanticChangeKind.CLEARED, bodyId.changeKind());
        assertEquals(new SemanticValue.IntegralValue(3), bodyId.before());
        assertFalse(bodyId.after().known());
    }

    @Test
    void registryFactReactivationIsNotANewDiscovery() {
        Fixture fixture = new Fixture();
        // Learn body 83 in detail.
        fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Scan",\
                "ScanType":"Detailed","SystemAddress":11,"BodyID":83,\
                "BodyName":"Alpha 83","PlanetClass":"Rocky body",\
                "Landable":true,"WasDiscovered":true,"WasMapped":false}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T14:01:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 83",\
                "BodyID":83}
                """);
        // Move away, then come back with an event carrying no body detail.
        fixture.apply("""
                {"timestamp":"2026-07-30T14:02:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 84",\
                "BodyID":84}
                """);
        List<SemanticStateChange> changes = fixture.apply("""
                {"timestamp":"2026-07-30T14:03:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 83",\
                "BodyID":83}
                """);

        SemanticStateChange planetClass =
                change(changes, SemanticField.PLANET_CLASS);
        assertEquals(
                SemanticChangeKind.ACTIVATED_FROM_CONTEXT,
                planetClass.changeKind(),
                "a re-visited body was not discovered again"
        );
        assertNotEquals(
                SemanticChangeKind.ESTABLISHED,
                planetClass.changeKind()
        );
        assertEquals(
                SemanticValueOrigin.STORED_CONTEXT,
                planetClass.origin()
        );
        assertEquals(
                new SemanticValue.SymbolicValue("Rocky body"),
                planetClass.after()
        );
    }

    @Test
    void firstScanOfABodyIsEstablishedNotActivated() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 5",\
                "BodyID":5}
                """);
        List<SemanticStateChange> changes = fixture.apply("""
                {"timestamp":"2026-07-30T14:01:00Z","event":"Scan",\
                "ScanType":"Detailed","SystemAddress":11,"BodyID":5,\
                "BodyName":"Alpha 5","PlanetClass":"Icy body",\
                "Landable":false,"WasDiscovered":false,"WasMapped":false}
                """);

        SemanticStateChange planetClass =
                change(changes, SemanticField.PLANET_CLASS);
        assertEquals(
                SemanticChangeKind.ESTABLISHED,
                planetClass.changeKind(),
                "this observation genuinely wrote the registry"
        );
        assertEquals(
                SemanticValueOrigin.OBSERVATION,
                planetClass.origin()
        );
    }

    @Test
    void missingSourceFieldDoesNotClearAStoredRegistryFact() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Scan",\
                "ScanType":"Detailed","SystemAddress":11,"BodyID":7,\
                "BodyName":"Alpha 7","PlanetClass":"Rocky body",\
                "Landable":true}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T14:01:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 7",\
                "BodyID":7}
                """);
        // A later scan without PlanetClass must not erase the stored class.
        List<SemanticStateChange> changes = fixture.apply("""
                {"timestamp":"2026-07-30T14:02:00Z","event":"Scan",\
                "ScanType":"Basic","SystemAddress":11,"BodyID":7,\
                "BodyName":"Alpha 7"}
                """);

        assertTrue(
                find(changes, SemanticField.PLANET_CLASS).isEmpty(),
                "an absent field is not a cleared field"
        );
        assertEquals(
                "Rocky body",
                fixture.currentState().planetClass()
        );
    }

    @Test
    void bodySwitchingDoesNotCarryDetailAcross() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Scan",\
                "ScanType":"Detailed","SystemAddress":11,"BodyID":83,\
                "BodyName":"Alpha 83","PlanetClass":"Rocky body"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T14:01:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 83",\
                "BodyID":83}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T14:02:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 84",\
                "BodyID":84}
                """);

        assertEquals(
                null,
                fixture.currentState().planetClass(),
                "body 84 must not inherit body 83's classification"
        );
    }

    @Test
    void clearingTheCurrentBodyDoesNotEraseTheRegistry() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Scan",\
                "ScanType":"Detailed","SystemAddress":11,"BodyID":9,\
                "BodyName":"Alpha 9","PlanetClass":"Metal rich body"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T14:01:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 9",\
                "BodyID":9}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T14:02:00Z",\
                "event":"SupercruiseEntry","StarSystem":"Alpha",\
                "SystemAddress":11}
                """);
        assertEquals(null, fixture.currentState().planetClass());

        // Returning proves the registry survived the clear.
        fixture.apply("""
                {"timestamp":"2026-07-30T14:03:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 9",\
                "BodyID":9}
                """);
        assertEquals(
                "Metal rich body",
                fixture.currentState().planetClass()
        );
    }

    @Test
    void bodyClassificationDimensionsStayIndependent() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Scan",\
                "ScanType":"Detailed","SystemAddress":11,"BodyID":4,\
                "BodyName":"Alpha 4","PlanetClass":"Icy body"}
                """);
        List<SemanticStateChange> changes = fixture.apply("""
                {"timestamp":"2026-07-30T14:01:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 4",\
                "BodyID":4,"BodyType":"Planet"}
                """);

        assertEquals(
                new SemanticValue.SymbolicValue("Planet"),
                change(changes, SemanticField.BROAD_BODY_TYPE).after()
        );
        assertEquals(
                new SemanticValue.SymbolicValue("Icy body"),
                change(changes, SemanticField.PLANET_CLASS).after()
        );
        assertTrue(
                find(changes, SemanticField.STAR_TYPE).isEmpty(),
                "star type is a separate dimension and was never established"
        );
    }

    @Test
    void unchangedValuesProduceNoDelta() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 1",\
                "BodyID":3}
                """);
        List<SemanticStateChange> changes = fixture.apply("""
                {"timestamp":"2026-07-30T14:01:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 1",\
                "BodyID":3}
                """);

        assertTrue(
                changes.isEmpty(),
                "a repeated identical observation is not a change"
        );
    }

    @Test
    void provenanceBelongsToTheObservationThatChangedTheValue() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"FSDJump",\
                "StarSystem":"Alpha","SystemAddress":11}
                """);
        PublishedObservation<JournalEventObservation> second =
                fixture.applyAndReturn("""
                        {"timestamp":"2026-07-30T14:01:00Z","event":"Scan",\
                        "ScanType":"Detailed","SystemAddress":11,"BodyID":6,\
                        "BodyName":"Alpha 6","PlanetClass":"Rocky body"}
                        """);

        for (SemanticStateChange change : fixture.lastChanges()) {
            assertEquals(
                    second.busSequence(),
                    change.provenance().busSequence()
            );
            assertEquals(
                    SemanticSourceRole.CONTEXT_ONLY,
                    change.provenance().sourceRole(),
                    "Scan is CONTEXT_ONLY and must say so"
            );
            assertEquals(
                    "Scan",
                    change.provenance().rawObservationType()
            );
        }
    }

    // ---------------------------------------------------------------------
    // Subject separation
    // ---------------------------------------------------------------------

    @Test
    void onFootBesideALandedShipWithADeployedSrvIsNotAContradiction() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Touchdown",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 1",\
                "BodyID":3,"PlayerControlled":true}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T14:01:00Z","event":"LaunchSRV",\
                "ID":8,"SRVType":"testbuggy","PlayerControlled":true}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T14:02:00Z","event":"Disembark",\
                "SRV":true,"Taxi":false,"Multicrew":false,"ID":8,\
                "StarSystem":"Alpha","SystemAddress":11}
                """);

        CurrentGameStateSnapshot state = fixture.currentState();
        assertEquals(CommanderLocationMode.ON_FOOT, state.commanderMode());
        assertEquals(FlightMode.LANDED, state.flightMode());
        assertEquals(
                CurrentGameStateSnapshot.VEHICLE_SRV,
                state.vehicleKind()
        );

        // Three independent subjects, so no slot conflicts with another.
        assertEquals(
                SemanticSubject.COMMANDER_PRESENCE,
                SemanticField.COMMANDER_MODE.subject()
        );
        assertEquals(
                SemanticSubject.ASSOCIATED_VEHICLE,
                SemanticField.VEHICLE_KIND.subject()
        );
        assertNotEquals(
                SemanticField.COMMANDER_MODE.subject(),
                SemanticField.VEHICLE_KIND.subject()
        );
        assertNotEquals(
                SemanticField.FLIGHT_MODE.subject(),
                SemanticField.COMMANDER_MODE.subject()
        );
    }

    @Test
    void flightModeUsesTheProvenNeutralSubject() {
        // Every writer of flightMode is a navigation operation, but nothing in
        // the repository establishes whether it describes the vessel or the
        // commander when the two differ. Assigning it to either would assert
        // ownership the code does not prove.
        assertEquals(
                SemanticSubject.NAVIGATION_CONTEXT,
                SemanticField.FLIGHT_MODE.subject()
        );
        assertNotEquals(
                SemanticSubject.PRIMARY_SHIP,
                SemanticField.FLIGHT_MODE.subject()
        );
        assertNotEquals(
                SemanticSubject.COMMANDER_PRESENCE,
                SemanticField.FLIGHT_MODE.subject()
        );
    }

    @Test
    void occupiedVehicleHasNoCanonicalFieldAndStaysUnresolved() {
        for (SemanticField field : SemanticField.values()) {
            assertNotEquals(
                    SemanticSubject.OCCUPIED_VEHICLE,
                    field.subject(),
                    field + " must not claim to establish occupancy"
            );
        }
    }

    @Test
    void onlyBodyRegistryFieldsCanBeActivatedFromContext() {
        for (SemanticField field : SemanticField.values()) {
            if (!field.bodyRegistryDerived()) {
                continue;
            }
            assertTrue(
                    field.subject() == SemanticSubject.CURRENT_BODY
                            || field.subject()
                            == SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
                    field + " is registry-derived but not a body subject"
            );
        }
        assertFalse(SemanticField.FLIGHT_MODE.bodyRegistryDerived());
        assertFalse(SemanticField.COMMANDER_MODE.bodyRegistryDerived());
        assertTrue(SemanticField.PLANET_CLASS.bodyRegistryDerived());
    }

    @Test
    void aLaterReadingAddsWhatItFoundAndRetractsNothing() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"ApproachBody",\
                "StarSystem":"Alpha","SystemAddress":11,"Body":"Alpha 2",\
                "BodyID":2}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T14:01:00Z",\
                "event":"SAASignalsFound","SystemAddress":11,"BodyID":2,\
                "BodyName":"Alpha 2","Signals":[\
                {"Type":"$SAA_SignalType_Biological;","Count":3}]}
                """);
        assertEquals(3, fixture.currentState().biologicalSignalCount());

        List<SemanticStateChange> changes = fixture.apply("""
                {"timestamp":"2026-07-30T14:02:00Z",\
                "event":"SAASignalsFound","SystemAddress":11,"BodyID":2,\
                "BodyName":"Alpha 2","Signals":[\
                {"Type":"$SAA_SignalType_Geological;","Count":4}]}
                """);

        SemanticStateChange geological =
                change(changes, SemanticField.GEOLOGICAL_SIGNAL_COUNT);
        assertEquals(
                SemanticChangeKind.ESTABLISHED,
                geological.changeKind(),
                "the first reading counted biology and said nothing about "
                        + "geology, so this reading establishes it"
        );
        assertFalse(
                geological.before().known(),
                "listing categories is not counting the ones it omits"
        );
        assertEquals(
                new SemanticValue.IntegralValue(4),
                geological.after()
        );
        assertEquals(
                3,
                fixture.currentState().biologicalSignalCount(),
                "a reading that does not mention biology is silence about it"
        );
        assertTrue(
                changes.stream().noneMatch(candidate ->
                        candidate.field()
                                == SemanticField.BIOLOGICAL_SIGNAL_COUNT),
                "nothing changed about the biological signals"
        );
    }

    // ---------------------------------------------------------------------

    private static SemanticStateChange change(
            List<SemanticStateChange> changes,
            SemanticField field
    ) {
        return find(changes, field).orElseThrow(() -> new AssertionError(
                "expected a change for " + field + " but found "
                        + changes.stream()
                        .map(candidate -> candidate.field().name())
                        .toList()
        ));
    }

    private static Optional<SemanticStateChange> find(
            List<SemanticStateChange> changes,
            SemanticField field
    ) {
        return changes.stream()
                .filter(change -> change.field() == field)
                .findFirst();
    }

    private static final class Fixture {

        private static final ObservationSource SOURCE =
                new ObservationSource("elite-journal", "delta-test");

        private final JournalLineParser parser = new JournalLineParser();
        private final JournalObservationAdapter adapter =
                new JournalObservationAdapter(SOURCE);
        private final CurrentGameStateProjector projector =
                new CurrentGameStateProjector();

        private CurrentGameStateProjection lastProjection;
        private long sourceOffset;
        private long busSequence;

        private List<SemanticStateChange> apply(String rawJson) {
            applyAndReturn(rawJson);
            return lastChanges();
        }

        private List<SemanticStateChange> lastChanges() {
            return lastProjection.semanticChanges();
        }

        private CurrentGameStateSnapshot currentState() {
            return lastProjection.currentState();
        }

        private PublishedObservation<JournalEventObservation> applyAndReturn(
                String rawJson
        ) {
            byte[] bytes = rawJson.strip().getBytes(StandardCharsets.UTF_8);
            ParsedJournalRecord parsed = assertInstanceOf(
                    ParsedJournalRecord.class,
                    parser.parse(new CompleteJournalRecord(
                            "Journal.delta-test.log",
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
