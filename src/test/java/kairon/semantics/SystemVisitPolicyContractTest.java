package kairon.semantics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.semantics.SystemVisitPolicy.SystemVisitState;
import kairon.semantics.SystemVisitTransition.Kind;
import kairon.semantics.SystemVisitTransition.Reason;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a visit begins and ends, stated once for both layers that keep one.
 *
 * <p>The behaviour graph's episode boundaries and the observer's novelty memory
 * were two implementations of this. Each was tested on its own, which is exactly
 * how they came to disagree: a memory that outlived its visit silenced a finding
 * the graph had just recorded. Every boundary either layer acts on is stated
 * here, on the policy both now ask.</p>
 */
final class SystemVisitPolicyContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FID = "F12345678";
    private static final long SHIP = 9L;

    /** A completed jump is an arrival, and the one that names a star. */
    @Test
    void aCompletedJumpBeginsAVisitAndCarriesItsArrivalStar() {
        SystemVisitTransition visit = SystemVisitPolicy.of(
                event("""
                        {"timestamp":"2026-07-30T10:00:00Z","event":"FSDJump",
                         "StarSystem":"Schieni","SystemAddress":23155,
                         "BodyID":0,"Body":"Schieni A"}
                        """),
                inSystem(23100L, 23155L)
        );

        assertEquals(Kind.BEGIN, visit.kind());
        assertEquals(Reason.HYPERSPACE_ARRIVAL, visit.reason());
        assertTrue(visit.arrival());
        assertTrue(visit.statesWhereTheShipIs());
        assertEquals(new BodyIdentity(23155L, 0L), visit.arrivalBody());
        assertEquals(23155L, visit.systemAddress());
    }

    /**
     * A restore is not an arrival, and never claims a star.
     *
     * <p>The body it names is where the ship is sitting, which may have been
     * reached an hour ago. Deriving an arrival from it would mint the
     * undiscovered-system milestone on a session restart.</p>
     */
    @Test
    void aRestoreIntoAnotherSystemBeginsAVisitWithNoArrival() {
        SystemVisitTransition visit = SystemVisitPolicy.of(
                location(),
                inSystem(23100L, 23155L)
        );

        assertEquals(Kind.BEGIN, visit.kind());
        assertEquals(Reason.SESSION_RESTORED, visit.reason());
        assertFalse(visit.arrival());
        assertTrue(visit.restore());
        assertNull(
                visit.arrivalBody(),
                "a restored session arrived nowhere"
        );
    }

    /** A restore naming the system already in progress changes nothing. */
    @Test
    void aRestoreOfTheSystemAlreadyInProgressContinuesTheVisit() {
        SystemVisitTransition visit = SystemVisitPolicy.of(
                location(),
                inSystem(23155L, 23155L)
        );

        assertEquals(Kind.CONTINUE, visit.kind());
        assertEquals(Reason.LOCATION_RESTATED, visit.reason());
        assertTrue(visit.restore(), "it is still a restore record");
    }

    /** With no visit open, a restore has nothing to be a restatement of. */
    @Test
    void aRestoreWithNoVisitInProgressBeginsOne() {
        SystemVisitTransition visit = SystemVisitPolicy.of(
                location(),
                new SystemVisitState(
                        false,
                        null,
                        FID,
                        SHIP,
                        23155L,
                        FID,
                        SHIP
                )
        );

        assertEquals(Kind.BEGIN, visit.kind());
        assertEquals(Reason.SESSION_RESTORED, visit.reason());
    }

    /**
     * A restore before the Commander and ship are known waits.
     *
     * <p>The behaviour graph holds such a record as a pending location and
     * opens the restored episode once an identity exists. A memory that opened
     * a visit anyway would reopen it the moment the identity arrived, so the
     * two layers now defer on the same record.</p>
     */
    @Test
    void aRestoreBeforeAnIdentityIsDeferred() {
        SystemVisitTransition beforeIdentity = SystemVisitPolicy.of(
                location(),
                new SystemVisitState(false, null, null, null, 23155L, null, null)
        );

        assertEquals(Kind.CONTINUE, beforeIdentity.kind());
        assertEquals(Reason.IDENTITY_PENDING, beforeIdentity.reason());
        assertTrue(beforeIdentity.restore());

        SystemVisitTransition halfAnIdentity = SystemVisitPolicy.of(
                location(),
                new SystemVisitState(false, null, null, null, 23155L, FID, null)
        );
        assertEquals(
                Reason.IDENTITY_PENDING,
                halfAnIdentity.reason(),
                "a Commander with no ship is not an identity"
        );

        SystemVisitTransition withIdentity = SystemVisitPolicy.of(
                location(),
                new SystemVisitState(false, null, null, null, 23155L, FID, SHIP)
        );
        assertEquals(
                Kind.BEGIN,
                withIdentity.kind(),
                "and the same record opens the visit once one exists"
        );
        assertEquals(
                Reason.VESSEL_CHANGED,
                withIdentity.reason(),
                "reported as the vessel becoming known, because a memory "
                        + "belonging to no vessel is not a visit of this one"
        );

        SystemVisitTransition afterTheVisitIsOwned = SystemVisitPolicy.of(
                location(),
                new SystemVisitState(true, 23155L, FID, SHIP, 23155L, FID, SHIP)
        );
        assertEquals(
                Reason.LOCATION_RESTATED,
                afterTheVisitIsOwned.reason(),
                "and a restore of the same system then changes nothing"
        );
    }

    /** A different ship is a different run of findings. */
    @Test
    void aShipChangeBeginsAVisitOnAnyRecord() {
        SystemVisitTransition visit = SystemVisitPolicy.of(
                event("""
                        {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                         "FID":"F12345678","ShipID":14,"Ship":"explorer_nx"}
                        """),
                new SystemVisitState(
                        true,
                        23155L,
                        FID,
                        SHIP,
                        23155L,
                        FID,
                        14L
                )
        );

        assertEquals(Kind.BEGIN, visit.kind());
        assertEquals(Reason.VESSEL_CHANGED, visit.reason());
        assertNull(visit.arrivalBody(), "switching ships arrives nowhere");
    }

    /** So is a different Commander. */
    @Test
    void aCommanderChangeBeginsAVisit() {
        SystemVisitTransition visit = SystemVisitPolicy.of(
                event("""
                        {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                         "FID":"F99999999","ShipID":9,"Ship":"explorer_nx"}
                        """),
                new SystemVisitState(
                        true,
                        23155L,
                        FID,
                        SHIP,
                        23155L,
                        "F99999999",
                        SHIP
                )
        );

        assertEquals(Reason.VESSEL_CHANGED, visit.reason());
    }

    /**
     * A jump that also changes vessel is still an arrival.
     *
     * <p>Both answers begin a visit, so the order only decides which one is
     * reported — and calling it a vessel change would lose the star it arrived
     * at, which is the one thing the arrival-star milestone needs.</p>
     */
    @Test
    void aJumpThatAlsoChangesVesselIsReportedAsTheArrival() {
        SystemVisitTransition visit = SystemVisitPolicy.of(
                event("""
                        {"timestamp":"2026-07-30T10:00:00Z","event":"FSDJump",
                         "StarSystem":"Schieni","SystemAddress":23155,
                         "BodyID":0,"Body":"Schieni A"}
                        """),
                new SystemVisitState(
                        true,
                        23100L,
                        FID,
                        SHIP,
                        23155L,
                        FID,
                        14L
                )
        );

        assertEquals(Kind.BEGIN, visit.kind());
        assertEquals(Reason.HYPERSPACE_ARRIVAL, visit.reason());
        assertEquals(new BodyIdentity(23155L, 0L), visit.arrivalBody());
    }

    /** The session ending ends the visit, whatever else is in progress. */
    @Test
    void aShutdownEndsTheVisit() {
        SystemVisitTransition visit = SystemVisitPolicy.of(
                event("""
                        {"timestamp":"2026-07-30T10:00:00Z","event":"Shutdown"}
                        """),
                inSystem(23155L, 23155L)
        );

        assertEquals(Kind.END, visit.kind());
        assertEquals(Reason.SESSION_ENDED, visit.reason());
        assertTrue(visit.sessionEnd());
        assertFalse(visit.statesWhereTheShipIs());
    }

    /** So does running out of records, and so does closing the source. */
    @Test
    void replayCompletionAndSourceCloseEndTheVisit() {
        assertEquals(Kind.END, SystemVisitPolicy.replayCompleted().kind());
        assertEquals(
                Reason.REPLAY_COMPLETED,
                SystemVisitPolicy.replayCompleted().reason()
        );
        assertEquals(Kind.END, SystemVisitPolicy.sourceClosed().kind());
        assertEquals(
                Reason.SOURCE_CLOSED,
                SystemVisitPolicy.sourceClosed().reason()
        );
    }

    /** An ordinary event is not a boundary at all. */
    @Test
    void anOrdinaryEventContinuesTheVisit() {
        SystemVisitTransition visit = SystemVisitPolicy.of(
                event("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"SupercruiseEntry","StarSystem":"Schieni",
                         "SystemAddress":23155}
                        """),
                inSystem(23155L, 23155L)
        );

        assertEquals(Kind.CONTINUE, visit.kind());
        assertEquals(Reason.ORDINARY_EVENT, visit.reason());
        assertFalse(visit.statesWhereTheShipIs());
    }

    /**
     * The arrival-star rule is one rule, and both layers reach it.
     *
     * <p>The observer's guard answers "already reported" with a flag and the
     * graph's survey policy answers it by scanning its own episode. What they
     * must not have is two versions of the rule itself.</p>
     */
    @Test
    void theArrivalStarRuleIsSharedAndFailsClosed() {
        JsonNode reading = parse("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"Scan",
                 "ScanType":"AutoScan","SystemAddress":23155,"BodyID":0,
                 "BodyName":"Schieni A","StarType":"K","WasDiscovered":false}
                """);
        BodyIdentity arrival = new BodyIdentity(23155L, 0L);

        assertTrue(SystemVisitPolicy.isVisitArrivalStarReading(
                arrival,
                reading,
                false
        ));
        assertFalse(
                SystemVisitPolicy.isVisitArrivalStarReading(
                        arrival,
                        reading,
                        true
                ),
                "once per visit"
        );
        assertFalse(
                SystemVisitPolicy.isVisitArrivalStarReading(
                        null,
                        reading,
                        false
                ),
                "a visit that was not an arrival admits none"
        );
        assertFalse(
                SystemVisitPolicy.isVisitArrivalStarReading(
                        new BodyIdentity(23155L, 4L),
                        reading,
                        false
                ),
                "another star of the same system is not the arrival star"
        );
        assertFalse(
                SystemVisitPolicy.isVisitArrivalStarReading(
                        arrival,
                        parse("""
                                {"timestamp":"2026-07-30T10:00:02Z",
                                 "event":"Scan","ScanType":"AutoScan",
                                 "SystemAddress":23155,"BodyID":0,
                                 "BodyName":"Schieni A","StarType":"K",
                                 "WasDiscovered":true}
                                """),
                        false
                ),
                "a star someone had already found confirms nothing"
        );
    }

    /**
     * Both layers reach the rule, and neither may hold its own copy.
     *
     * <p>Checked structurally, because the point is not that two
     * implementations currently agree but that there is one. The graph's survey
     * policy and the observer's novelty guard must each name the shared
     * policy.</p>
     */
    @Test
    void bothLayersCallTheSharedPolicy() throws Exception {
        assertTrue(
                callsSharedPolicy(
                        "kairon.behavior.classify.BodySurveySelectionPolicy"
                ),
                "the graph's survey policy"
        );
        assertTrue(
                callsSharedPolicy("kairon.observer.BodySurveyNoveltyGuard"),
                "the observer's novelty guard"
        );
    }

    // ------------------------------------------------------------- fixtures

    /**
     * Whether a class names the shared policy in its own source.
     *
     * <p>Reflection cannot see a static call, so the check is on the source
     * text of the class that has to make it. Crude, and enough: the failure
     * being prevented is a layer quietly growing its own rule back, which
     * starts by no longer mentioning the shared one.</p>
     */
    private static boolean callsSharedPolicy(String className)
            throws IOException {
        return java.nio.file.Files.readString(
                java.nio.file.Path.of(
                        "src", "main", "java",
                        className.replace('.', '/') + ".java"
                ),
                StandardCharsets.UTF_8
        ).contains("SystemVisitPolicy.isVisitArrivalStarReading");
    }

    private static SystemVisitState inSystem(
            Long visitSystemAddress,
            Long observedSystemAddress
    ) {
        return new SystemVisitState(
                true,
                visitSystemAddress,
                FID,
                SHIP,
                observedSystemAddress,
                FID,
                SHIP
        );
    }

    private static JournalEventObservation location() {
        return event("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Location",
                 "StarSystem":"Schieni","SystemAddress":23155,"BodyID":0,
                 "Body":"Schieni A","Docked":false}
                """);
    }

    /**
     * One journal record, built the way production builds it.
     *
     * <p>The real parser and the real observation adapter, so the payload type
     * the policy sees is the one the pipeline would hand it.</p>
     */
    private static JournalEventObservation event(String rawJson) {
        ParsedJournalRecord parsed = (ParsedJournalRecord) new JournalLineParser()
                .parse(new CompleteJournalRecord(
                        "Journal.visit-policy-test.log",
                        0L,
                        rawJson.strip().getBytes(StandardCharsets.UTF_8)
                ));
        return new JournalObservationAdapter(
                new ObservationSource("elite-journal", "visit-policy-test")
        ).adapt(
                parsed,
                ObservationCaptureMode.REPLAY,
                parsed.optionalJournalTimestamp().orElseThrow()
        ).payload();
    }

    private static JsonNode parse(String rawJson) {
        try {
            return JSON.readTree(rawJson);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
