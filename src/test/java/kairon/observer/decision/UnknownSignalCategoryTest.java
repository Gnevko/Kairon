package kairon.observer.decision;

import kairon.behavior.normalize.NormalizedEventType;
import kairon.semantics.SemanticField;
import kairon.state.CurrentGameStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static kairon.observer.decision.SemanticPipelineAssertions
        .assertChangesAndContextPartition;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertNoProviderTurn;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertNoStaleChanges;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertOccurrenceAndEventAgree;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertSourceOrder;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertUnknownNotMaterialized;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A category nobody counted has no count.
 *
 * <p>The projector used to write a zero for the two published categories the
 * moment any reading listed its signals, so a system scan finding one biological
 * signal also asserted that there are no geological ones. A measured zero and an
 * unmeasured category are different facts, and the model was given no way to
 * tell them apart — while the request's whole contract is that an absent field
 * means unknown.</p>
 *
 * <p>Nothing here introduces a way to say "surveyed and found none". That claim
 * would need a source that makes it, and none does.</p>
 */
final class UnknownSignalCategoryTest {

    /** A reading that counted biology says nothing about geology. */
    @Test
    void aBiologicalOnlyReadingLeavesGeologyUnknown(@TempDir Path directory) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            arrived(harness);
            harness.journal(signals("10:01:00Z", "FSSBodySignals", BIO_1))
                    .closeBatch();
            PipelineTrace trace = harness.trace();
            CurrentGameStateSnapshot state = trace.finalState().orElseThrow();

            assertEquals(1, state.biologicalSignalCount());
            assertNull(state.geologicalSignalCount(), "nobody counted geology");
            assertUnknownNotMaterialized(
                    trace,
                    SemanticField.GEOLOGICAL_SIGNAL_COUNT
            );
            assertEquals(
                    1L,
                    occurrences(trace, NormalizedEventType
                            .FSS_BODY_SIGNALS_FOUND),
                    "the finding is still a finding"
            );
            assertEquals(
                    """
                    {"events":[{"id":1,"kind":"BODY_SIGNALS_FOUND",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "signals":[{"type":"BIOLOGICAL","count":1}]}],\
                    "trajectory":{"recent":["SYSTEM_ENTERED",\
                    "BODY_APPROACHED"]}}""",
                    trace.turns().getLast().userMessage(),
                    "the set already says what was found, so nothing repeats it"
            );
        }
    }

    /** And the other way round. */
    @Test
    void aGeologicalOnlyReadingLeavesBiologyUnknown(@TempDir Path directory) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            arrived(harness);
            harness.journal(signals("10:01:00Z", "FSSBodySignals", GEO_2))
                    .closeBatch();
            PipelineTrace trace = harness.trace();
            CurrentGameStateSnapshot state = trace.finalState().orElseThrow();

            assertEquals(2, state.geologicalSignalCount());
            assertNull(
                    state.biologicalSignalCount(),
                    "nobody counted biology"
            );
            assertUnknownNotMaterialized(
                    trace,
                    SemanticField.BIOLOGICAL_SIGNAL_COUNT
            );
            assertFalse(
                    trace.turns().getLast().userMessage()
                            .contains("biologicalSignals"),
                    trace.turns().getLast().userMessage()
            );
            assertEquals(
                    "[{\"type\":\"GEOLOGICAL\",\"count\":2}]",
                    trace.turns().getLast().events().get(0)
                            .path("signals").toString()
            );
        }
    }

    /** A reading of nothing, on a body nothing had established. */
    @Test
    void anEmptyReadingOnAnUnknownBodyEstablishesNothing(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            arrived(harness);
            PipelineTrace before = harness.trace();

            harness.journal(signals("10:01:00Z", "SAASignalsFound", ""))
                    .closeBatch();
            PipelineTrace trace = harness.trace();
            CurrentGameStateSnapshot state = trace.finalState().orElseThrow();

            assertNull(state.biologicalSignalCount());
            assertNull(state.geologicalSignalCount());
            assertUnknownNotMaterialized(
                    trace,
                    SemanticField.BIOLOGICAL_SIGNAL_COUNT
            );
            assertUnknownNotMaterialized(
                    trace,
                    SemanticField.GEOLOGICAL_SIGNAL_COUNT
            );
            assertEquals(
                    0L,
                    occurrences(trace, NormalizedEventType.SAA_SIGNALS_FOUND)
            );
            assertNoProviderTurn(before, trace);
        }
    }

    /**
     * A finding is stated once: in the set that found it.
     *
     * <p>The signal set and the canonical count are the same fact, and a change
     * repeating it invited the model to read one finding as two. The set is the
     * event's own account of what the scanner reported, so it is the one that
     * stays.</p>
     */
    @Test
    void aReportedCategoryIsNotRepeatedAsAChangeOrAsContext(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            arrived(harness);
            harness.journal(signals("10:01:00Z", "SAASignalsFound", GEO_2))
                    .closeBatch();
            PipelineTrace.TurnView turn = harness.trace().turns().getLast();

            assertEquals(
                    """
                    {"events":[{"id":1,"kind":"BODY_SIGNALS_FOUND",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "signals":[{"type":"GEOLOGICAL","count":2}]}],\
                    "trajectory":{"recent":["SYSTEM_ENTERED",\
                    "BODY_APPROACHED"]}}""",
                    turn.userMessage()
            );
            assertFalse(
                    turn.userMessage().contains("geologicalSignals"),
                    "the canonical name appears nowhere; the set said it"
            );
        }
    }

    /**
     * What this reading found is the event; what an earlier one found is
     * context.
     *
     * <p>Both are true of the body, and only one of them is news. The set states
     * the geological count, so it is not repeated as a change; the biological
     * count this reading says nothing about is standing background, and that is
     * what {@code context} is for.</p>
     */
    @Test
    void anEarlierCategoryStaysContextWhileTheReportedOneDoesNot(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            arrived(harness);
            harness.journal(signals("10:01:00Z", "FSSBodySignals", BIO_1))
                    .closeBatch();
            harness.journal(signals("10:02:00Z", "SAASignalsFound", GEO_2))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertEquals(
                    """
                    {"events":[{"id":1,"kind":"BODY_SIGNALS_FOUND",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "signals":[{"type":"GEOLOGICAL","count":2}]}],\
                    "context":{"body":{"biologicalSignals":1}},\
                    "trajectory":{"recent":["SYSTEM_ENTERED",\
                    "BODY_APPROACHED","BODY_SIGNALS_FOUND"]}}""",
                    trace.turns().getLast().userMessage()
            );
            assertEquals(1, trace.finalState().orElseThrow()
                    .biologicalSignalCount());
            assertEquals(2, trace.finalState().orElseThrow()
                    .geologicalSignalCount());
            assertChangesAndContextPartition(trace);
        }
    }

    /** The cross-layer contracts, on the sequence this fix changed. */
    @Test
    void theCorrectedReadingSatisfiesEveryCrossLayerContract(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness = harness(directory)) {
            arrived(harness);
            harness.journal(signals("10:01:00Z", "FSSBodySignals", BIO_1))
                    .closeBatch();
            harness.journal(signals("10:02:00Z", "SAASignalsFound", GEO_2))
                    .closeBatch();
            PipelineTrace trace = harness.trace();
            CurrentGameStateSnapshot state = trace.finalState().orElseThrow();

            assertEquals(1, state.biologicalSignalCount(), "still counted");
            assertEquals(2, state.geologicalSignalCount(), "now counted too");
            assertNoStaleChanges(trace);
            assertChangesAndContextPartition(trace);
            assertSourceOrder(trace);
            assertOccurrenceAndEventAgree(
                    trace,
                    trace.lastObservationOfType("SAASignalsFound")
                            .busSequence(),
                    NormalizedEventType.SAA_SIGNALS_FOUND
            );
            assertFalse(
                    trace.turns().getLast().userMessage().contains(":0"),
                    "no invented zero anywhere: "
                            + trace.turns().getLast().userMessage()
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private static final String BIO_1 =
            "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}";
    private static final String GEO_2 =
            "{\"Type\":\"$SAA_SignalType_Geological;\",\"Count\":2}";

    private static SemanticPipelineHarness harness(Path directory) {
        return SemanticPipelineHarness.create(directory);
    }

    private static long occurrences(
            PipelineTrace trace,
            NormalizedEventType eventType
    ) {
        return trace.occurrences().stream()
                .filter(occurrence -> occurrence.eventType().equals(eventType))
                .count();
    }

    private static void arrived(SemanticPipelineHarness harness) {
        harness.journal("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """)
                .journal("""
                        {"timestamp":"2026-07-30T10:00:01Z","event":"FSDJump",
                         "StarSystem":"Schieni","SystemAddress":23155,
                         "JumpDist":8.5,"FuelUsed":0.4,"FuelLevel":30.2}
                        """)
                .journal("""
                        {"timestamp":"2026-07-30T10:00:30Z",
                         "event":"ApproachBody","StarSystem":"Schieni",
                         "SystemAddress":23155,"Body":"Schieni 4 a",
                         "BodyID":20}
                        """)
                .closeBatch();
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
}
