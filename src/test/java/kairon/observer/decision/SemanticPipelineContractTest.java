package kairon.observer.decision;

import kairon.behavior.model.EpisodeEntrySource;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.semantics.SemanticField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static kairon.observer.decision.Journal.jump;
import static kairon.observer.decision.Journal.loadGame;
import static kairon.observer.decision.Journal.location;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertChangesAndContextPartition;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertChangeAttributionStaysInternal;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertDuplicateSuppressed;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertNewStructuralTrigger;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertNoProviderTurn;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertNoStaleChanges;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertOccurrenceAndEventAgree;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertRestoreOnly;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertSourceOrder;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertUnknownNotMaterialized;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one observation sequence must mean, on every layer at once.
 *
 * <p>These are the contracts a single-layer test cannot state. Each one runs the
 * production pipeline through {@link SemanticPipelineHarness} and checks
 * canonical state, the behaviour graph, observer admission, the provider and the
 * exact serialized request together — because every defect this project has
 * fixed twice was a disagreement between two of those, with both layers' own
 * tests green.</p>
 *
 * <p>These tests assert the contract as it holds today. Where the audit proved a
 * contract does <em>not</em> hold yet, the target is stated in
 * {@code SemanticPipelineKnownInvalidContractTest} instead, disabled, so that
 * nothing here quietly certifies a defect as intended.</p>
 */
final class SemanticPipelineContractTest {

    /** A1: a restoring Location establishes state and nothing else. */
    @Test
    void aRestoringLocationIsStateOnlyForGraphAndObserver(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            harness.journal(loadGame("10:00:00Z"))
                    .journal(location("10:00:01Z", 2001, "Restore A"))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertEquals(
                    "Restore A",
                    trace.finalState().orElseThrow().systemName()
            );
            assertEquals(1, trace.episodes().size());
            assertEquals(
                    EpisodeEntrySource.LOCATION_RESTORE,
                    trace.episodes().getFirst().entrySource()
            );
            assertEquals(
                    List.of(),
                    trace.episodes().getFirst().occurrenceTypes(),
                    "the Commander is already here; nothing happened"
            );
            assertTrue(trace.cursor().isEmpty(), "no position to report");
            assertEquals(0, trace.providerCalls());
            assertRestoreOnly(
                    trace,
                    trace.lastObservationOfType("Location").busSequence()
            );
        }
    }

    /** A2: the first real event of a restored visit takes no predecessor. */
    @Test
    void theFirstStructuralEventAfterARestoreOpensTheVisitsHistory(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            harness.journal(loadGame("10:00:00Z"))
                    .journal(location("10:00:01Z", 2001, "Restore A"))
                    .journal("""
                            {"timestamp":"2026-07-30T10:00:05Z",
                             "event":"StartJump","JumpType":"Supercruise"}
                            """)
                    .journal("""
                            {"timestamp":"2026-07-30T10:00:06Z",
                             "event":"SupercruiseEntry",
                             "StarSystem":"Restore A","SystemAddress":2001}
                            """)
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertEquals(
                    List.of(
                            NormalizedEventType.SUPERCRUISE_JUMP_STARTED,
                            NormalizedEventType.SUPERCRUISE_ENTRY
                    ),
                    trace.episodes().getFirst().occurrenceTypes()
            );
            assertEquals(
                    List.of(new PipelineTrace.TransitionView(
                            NormalizedEventType.SUPERCRUISE_JUMP_STARTED,
                            NormalizedEventType.SUPERCRUISE_ENTRY
                    )),
                    trace.episodes().getFirst().transitions(),
                    "the first event of a restored visit follows nothing"
            );
            long entry = trace.lastObservationOfType("SupercruiseEntry")
                    .busSequence();
            assertNewStructuralTrigger(
                    trace,
                    entry,
                    NormalizedEventType.SUPERCRUISE_ENTRY,
                    "SUPERCRUISE_ENTERED"
            );
            assertSourceOrder(trace);
        }
    }

    /** A3: a scanner finding is structural and is reported. */
    @Test
    void aScannerFindingIsStructuralAndReported(@TempDir Path directory) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            arrived(harness);
            PipelineTrace before = harness.trace();

            harness.journal(signals("10:01:00Z", "FSSBodySignals", BIO_1))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            long reading = trace.lastObservationOfType("FSSBodySignals")
                    .busSequence();
            assertNewStructuralTrigger(
                    trace,
                    reading,
                    NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                    "BODY_SIGNALS_FOUND"
            );
            assertOccurrenceAndEventAgree(
                    trace,
                    reading,
                    NormalizedEventType.FSS_BODY_SIGNALS_FOUND
            );
            assertEquals(
                    before.providerCalls() + 1,
                    trace.providerCalls()
            );
        }
    }

    /**
     * A4: the same instrument saying the same thing twice is one finding.
     *
     * <p>Two instruments saying it are two findings, because they are not
     * saying the same thing. The system scanner counts the signals a body
     * carries; the surface scanner fires probes and names the organisms.
     * Compared by count alone, the reading that names them was silenced by the
     * one that cannot name anything — so both halves are asserted here, and the
     * pair is the contract.</p>
     */
    @Test
    void aRepeatedScannerResultCostsNothingAnywhere(@TempDir Path directory) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            arrived(harness);
            harness.journal(signals("10:01:00Z", "FSSBodySignals", BIO_1))
                    .closeBatch();
            PipelineTrace before = harness.trace();

            harness.journal(signals("10:02:00Z", "FSSBodySignals", BIO_1))
                    .closeBatch();
            PipelineTrace afterRepeat = harness.trace();

            assertDuplicateSuppressed(before, afterRepeat);

            harness.journal(signals("10:03:00Z", "SAASignalsFound", BIO_1))
                    .closeBatch();
            PipelineTrace afterSurvey = harness.trace();

            assertEquals(
                    afterRepeat.providerCalls() + 1,
                    afterSurvey.providerCalls(),
                    () -> "the probes reported and nobody was told: "
                            + afterSurvey.describe()
            );
            assertEquals(
                    2,
                    afterSurvey.occurrences().stream()
                            .filter(occurrence -> occurrence.eventType()
                                    .equals(NormalizedEventType
                                            .FSS_BODY_SIGNALS_FOUND)
                                    || occurrence.eventType()
                                    .equals(NormalizedEventType
                                            .SAA_SIGNALS_FOUND))
                            .count(),
                    "one occurrence per instrument, and no more"
            );
        }
    }

    /** A5: a model-facing event need not be structural. */
    @Test
    void aConversationalTriggerHasNoOccurrenceAndNoTrajectory(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            arrived(harness);
            PipelineTrace before = harness.trace();

            harness.journal("""
                    {"timestamp":"2026-07-30T10:01:00Z","event":"ReceiveText",
                     "Channel":"player","From":"Ana","Message":"see you there",
                     "Message_Localised":"see you there"}
                    """)
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            long message = trace.lastObservationOfType("ReceiveText")
                    .busSequence();
            assertEquals(
                    before.providerCalls() + 1,
                    trace.providerCalls(),
                    "a message is worth telling the model about"
            );
            assertEquals(
                    "MESSAGE_RECEIVED",
                    trace.modelFacingKinds().getLast()
            );
            assertTrue(
                    trace.occurrenceOf(message).isEmpty(),
                    "a message is not something the ship did"
            );
            assertEquals(
                    before.occurrences().size(),
                    trace.occurrences().size()
            );
        }
    }

    /**
     * A6: a structural observation the model is never shown.
     *
     * <p>{@code StartJump} is {@code DIAGNOSTIC_ONLY} for the observer and
     * structural for the graph. It opens no turn of its own — and its normalized
     * name still reaches the model later, through the trajectory. That is the
     * contract as it stands, and stating it here is the point: "does not trigger
     * a turn" and "never appears in model input" are different claims.</p>
     */
    @Test
    void aStructuralModelSilentObservationStillShapesTheTrajectory(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            arrived(harness);
            PipelineTrace before = harness.trace();

            harness.journal("""
                    {"timestamp":"2026-07-30T10:01:00Z","event":"StartJump",
                     "JumpType":"Supercruise"}
                    """)
                    .closeBatch();
            PipelineTrace afterJump = harness.trace();

            long jump = afterJump.lastObservationOfType("StartJump")
                    .busSequence();
            assertTrue(
                    afterJump.occurrenceOf(jump).isPresent(),
                    "the graph records the jump"
            );
            assertEquals(
                    NormalizedEventType.SUPERCRUISE_JUMP_STARTED,
                    afterJump.occurrenceOf(jump).orElseThrow().eventType()
            );
            assertNoProviderTurn(before, afterJump);

            harness.journal("""
                    {"timestamp":"2026-07-30T10:01:06Z",
                     "event":"SupercruiseEntry","StarSystem":"Schieni",
                     "SystemAddress":23155}
                    """)
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertEquals(
                    before.providerCalls() + 1,
                    trace.providerCalls(),
                    () -> "the entry itself is worth a turn: "
                            + trace.describe()
            );
        }
    }

    /** A7: a background change that is still true is still told. */
    @Test
    void aBackgroundChangeThatIsStillCurrentSurvives(@TempDir Path directory) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            harness.journal(loadGame("10:00:00Z"))
                    .journal(jump("10:00:01Z", 23155, "Schieni"))
                    .closeBatch();
            harness.journal("""
                    {"timestamp":"2026-07-30T10:01:00Z","event":"LaunchSRV",
                     "SRVType":"testbuggy","Loadout":"starter",
                     "ID":7,"PlayerControlled":true}
                    """)
                    .settleProjection();
            harness.journal("""
                    {"timestamp":"2026-07-30T10:01:02Z","event":"Cargo",
                     "Vessel":"SRV","Count":0,"Inventory":[]}
                    """)
                    .settleProjection();
            harness.journal("""
                    {"timestamp":"2026-07-30T10:01:10Z","event":"Touchdown",
                     "StarSystem":"Schieni","SystemAddress":23155,
                     "Body":"Schieni 4 a","BodyID":20,"PlayerControlled":true,
                     "Latitude":1.0,"Longitude":2.0}
                    """)
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertNoStaleChanges(trace);
            assertChangeAttributionStaysInternal(trace);
            assertChangesAndContextPartition(trace);
        }
    }

    /** A8: two records of one moment stay in journal order. */
    @Test
    void aMultiEventBatchKeepsJournalOrder(@TempDir Path directory) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            arrived(harness);
            PipelineTrace before = harness.trace();

            harness.journal(signals("10:01:00Z", "FSSBodySignals", BIO_1))
                    .journal(scan("10:01:00Z"))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertEquals(
                    before.providerCalls() + 1,
                    trace.providerCalls(),
                    "two records of one moment are one batch"
            );
            PipelineTrace.TurnView turn = trace.turns().getLast();
            assertEquals(
                    List.of("BODY_SIGNALS_FOUND", "BODY_SCANNED"),
                    turn.eventKinds()
            );
            assertEquals(List.of(1, 2), turn.eventIds());
            assertEquals(
                    List.of(
                            trace.lastObservationOfType("FSSBodySignals")
                                    .busSequence(),
                            trace.lastObservationOfType("Scan").busSequence()
                    ),
                    turn.triggerBusSequences()
            );
            assertEquals(
                    List.of(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                            NormalizedEventType.BODY_SCANNED
                    ),
                    trace.episodes().getFirst().occurrenceTypes()
                            .subList(2, 4)
            );
            assertSourceOrder(trace);
        }
    }

    /** A9: a fact nothing established is absent everywhere. */
    @Test
    void anUnestablishedFactIsAbsentRatherThanDefaulted(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            arrived(harness);
            harness.journal("""
                    {"timestamp":"2026-07-30T10:01:00Z","event":"Touchdown",
                     "StarSystem":"Schieni","SystemAddress":23155,
                     "Body":"Schieni 4 a","BodyID":20,"PlayerControlled":true,
                     "Latitude":1.0,"Longitude":2.0}
                    """)
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertUnknownNotMaterialized(
                    trace,
                    SemanticField.DISTANCE_FROM_ARRIVAL_LS
            );
            assertUnknownNotMaterialized(trace, SemanticField.LANDABLE);
            assertUnknownNotMaterialized(trace, SemanticField.PLANET_CLASS);
            assertFalse(
                    trace.turns().getLast().userMessage()
                            .contains("distanceFromArrivalLs"),
                    "nothing measured the distance, so nothing says it"
            );
        }
    }

    /** A10: an empty reading is not a finding and retracts nothing. */
    @Test
    void anEmptyScannerResultIsNotAFinding(@TempDir Path directory) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            arrived(harness);
            harness.journal(signals("10:01:00Z", "FSSBodySignals", BIO_1))
                    .closeBatch();
            PipelineTrace before = harness.trace();

            harness.journal(signals("10:02:00Z", "SAASignalsFound", ""))
                    .closeBatch();
            PipelineTrace after = harness.trace();

            assertNoProviderTurn(before, after);
            assertDuplicateSuppressed(before, after);
            assertEquals(
                    1,
                    after.finalBody(23155L, 20L).biologicalSignalCount(),
                    "finding nothing retracts nothing"
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private static final String BIO_1 =
            "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}";

    private static SemanticPipelineHarness harness(Path directory) {
        return SemanticPipelineHarness.create(directory);
    }

    /**
     * A jump into a system with a body selected, settled into its own turns.
     *
     * <p>Deliberately closes the batch: every test that follows it is about
     * what happens next, and leaving the arrival open would put it in the batch
     * under test.</p>
     */
    private static void arrived(SemanticPipelineHarness harness) {
        harness.journal(loadGame("10:00:00Z"))
                .journal(jump("10:00:01Z", 23155, "Schieni"))
                .journal(approach("10:00:30Z"))
                .closeBatch();
    }


    private static String approach(String time) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"ApproachBody\",\"StarSystem\":\"Schieni\","
                + "\"SystemAddress\":23155,\"Body\":\"Schieni 4 a\","
                + "\"BodyID\":20}";
    }

    private static String signals(
            String time,
            String eventName,
            String reported
    ) {
        return "{\"timestamp\":\"2026-07-30T" + time + "\",\"event\":\""
                + eventName + "\",\"StarSystem\":\"Schieni\","
                + "\"SystemAddress\":23155,\"BodyID\":20,"
                + "\"BodyName\":\"Schieni 4 a\",\"Signals\":["
                + reported + "]}";
    }

    private static String scan(String time) {
        return "{\"timestamp\":\"2026-07-30T" + time + "\",\"event\":\"Scan\","
                + "\"ScanType\":\"Detailed\",\"StarSystem\":\"Schieni\","
                + "\"SystemAddress\":23155,\"BodyID\":20,"
                + "\"BodyName\":\"Schieni 4 a\",\"PlanetClass\":\"Icy body\","
                + "\"Landable\":true,\"WasDiscovered\":false,"
                + "\"WasMapped\":false,\"DistanceFromArrivalLS\":812.0}";
    }
}
