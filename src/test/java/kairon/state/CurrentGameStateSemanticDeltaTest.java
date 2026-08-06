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
 * <p>Every change here is something an observation did. The case that used to
 * be decisive — a body fact reappearing because a previously seen body was
 * selected again — cannot arise: body detail is the current-system registry's
 * and is not canonical state at all (ADR-0025).</p>
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

    /**
     * Coming back to a body Kairon has seen before changes the selection only.
     *
     * <p>The ice, the counts and the fact that nobody has landed here were all
     * true before this approach and are still true after it. They used to
     * arrive as a delta and had to be marked as a recall so that nothing read
     * them as news; now they are simply not canonical state, so there is
     * nothing to mark.</p>
     */
    @Test
    void revisitingAKnownBodyChangesOnlyTheSelection() {
        Fixture fixture = new Fixture();
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

        assertEquals(
                List.of(SemanticField.BODY_ID, SemanticField.BODY_NAME),
                changes.stream()
                        .map(SemanticStateChange::field)
                        .sorted()
                        .toList()
        );
    }

    /** A scanner reading is not a canonical delta at all. */
    @Test
    void aScanChangesNothingInCanonicalState() {
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

        assertTrue(
                changes.isEmpty(),
                "what a scan establishes belongs to the body, not to where "
                        + "the ship is"
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
                        {"timestamp":"2026-07-30T14:01:00Z",\
                        "event":"Location","StarSystem":"Alpha",\
                        "SystemAddress":11,"Body":"Alpha 6","BodyID":6,\
                        "Docked":false,"OnFoot":false}
                        """);

        for (SemanticStateChange change : fixture.lastChanges()) {
            assertEquals(
                    second.busSequence(),
                    change.provenance().busSequence()
            );
            assertEquals(
                    SemanticSourceRole.CONTEXT_ONLY,
                    change.provenance().sourceRole(),
                    "Location is CONTEXT_ONLY and must say so"
            );
            assertEquals(
                    "Location",
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

    /**
     * What a body is like is not canonical state's to answer.
     *
     * <p>The identities stay, because one fact has one name whoever states it.
     * What changed is which source answers them — and only a field canonical
     * state answers can ever be a delta of it.</p>
     */
    @Test
    void bodyDetailIsNotAnsweredByCanonicalState() {
        assertFalse(SemanticField.PLANET_CLASS.answeredByCanonicalState());
        assertFalse(SemanticField.STAR_TYPE.answeredByCanonicalState());
        assertFalse(SemanticField.BROAD_BODY_TYPE.answeredByCanonicalState());
        assertFalse(SemanticField.LANDABLE.answeredByCanonicalState());
        assertFalse(
                SemanticField.BIOLOGICAL_SIGNAL_COUNT
                        .answeredByCanonicalState()
        );
        assertFalse(SemanticField.BODY_HAS_BIOLOGY.answeredByCanonicalState());

        assertTrue(SemanticField.BODY_ID.answeredByCanonicalState());
        assertTrue(SemanticField.BODY_NAME.answeredByCanonicalState());
        assertTrue(SemanticField.FLIGHT_MODE.answeredByCanonicalState());
        assertTrue(SemanticField.COMMANDER_MODE.answeredByCanonicalState());

        for (SemanticField field : SemanticField.values()) {
            if (field.answeredByCanonicalState()) {
                continue;
            }
            assertTrue(
                    field.subject() == SemanticSubject.CURRENT_BODY
                            || field.subject()
                            == SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
                    field + " is not canonical state's but is not a body fact"
            );
        }
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
