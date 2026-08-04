package kairon.observer.decision;

import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapping a body does not scan it again.
 *
 * <p>Completing a surface survey makes the game re-emit the body's whole scan
 * record. Every classification and flag is identical — the same class, the same
 * atmosphere, the same {@code WasMapped: false} — and the survey itself is
 * reported by its own record beside it. The one field that had moved was
 * {@code DistanceFromArrivalLS}, because the body had travelled some fifty
 * kilometres along its orbit in the four minutes the survey took.</p>
 *
 * <p>That was enough: the position was part of the identity of a reading, so
 * the restatement compared as a different result. The model was told a second
 * time that the body had been scanned, and the graph recorded a second
 * occurrence with a transition out of the completed survey that nobody had
 * made. Every {@code SAAScanComplete} in the observed journal was followed by
 * one.</p>
 */
final class PostMappingRescanTest {

    /** The mapping is reported; the re-emitted scan is not. */
    @Test
    void aReEmittedScanAfterMappingIsNotASecondFinding(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            surveyedAndScanned(harness);
            harness.journal(ObservationCaptureMode.LIVE, mappingComplete())
                    .journal(ObservationCaptureMode.LIVE, scan(1833.953549))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertEquals(
                    List.of(
                            "SYSTEM_JUMP",
                            "BODY_SCANNED",
                            "BODY_MAPPING_COMPLETED"
                    ),
                    trace.modelFacingKinds(),
                    "the scan was told once and the mapping once: "
                            + trace.describe()
            );
            assertEquals(
                    3,
                    trace.providerCalls(),
                    "and the restatement opened no turn of its own"
            );
        }
    }

    /**
     * The graph does not record the restatement either.
     *
     * <p>Both sides read the same rule, so a reading that is not a finding for
     * the model is not an occurrence for the graph — and the survey keeps no
     * outgoing transition into a scan that never happened.</p>
     */
    @Test
    void theGraphRecordsOneScanOccurrence(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            surveyedAndScanned(harness);
            harness.journal(ObservationCaptureMode.LIVE, mappingComplete())
                    .journal(ObservationCaptureMode.LIVE, scan(1833.953549))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertEquals(
                    1,
                    trace.occurrences().stream()
                            .filter(occurrence -> occurrence.eventType()
                                    .equals(NormalizedEventType.BODY_SCANNED))
                            .count(),
                    "one scan happened, so one occurrence: " + trace.describe()
            );
        }
    }

    /**
     * The fresh distance still reaches canonical state.
     *
     * <p>Declining a reading is not ignoring it. The record is still projected,
     * so what the body is now is what the model is told next — it simply is not
     * a second discovery.</p>
     */
    @Test
    void theUpdatedDistanceIsStillApplied(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(ObservationCaptureMode.LIVE, jump())
                    .closeBatch();
            // Standing at the body, so canonical state answers for this one.
            harness.journal(ObservationCaptureMode.LIVE, approach())
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, scan(1833.95371))
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, mappingComplete())
                    .journal(ObservationCaptureMode.LIVE, scan(1833.953549))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertEquals(
                    1833.953549,
                    trace.finalState().orElseThrow().distanceFromArrivalLs(),
                    "the body moved, and canonical state knows it"
            );
            assertEquals(
                    1,
                    trace.modelFacingKinds().stream()
                            .filter("BODY_SCANNED"::equals)
                            .count(),
                    "while the model heard about one scan: "
                            + trace.describe()
            );
        }
    }

    /**
     * A reading that really says something new is still a finding.
     *
     * <p>The guard must not have become a rule that one body is scanned once.
     * A second detailed scan reporting a body nobody had mapped as now mapped
     * is a different result and opens its own turn.</p>
     */
    @Test
    void areadingThatChangedAFactIsStillNew(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            surveyedAndScanned(harness);
            harness.journal(ObservationCaptureMode.LIVE, mappedScan())
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertEquals(
                    List.of("SYSTEM_JUMP", "BODY_SCANNED", "BODY_SCANNED"),
                    trace.modelFacingKinds(),
                    "a body that is now mapped is a different reading: "
                            + trace.describe()
            );
            assertTrue(
                    trace.providerCalls() >= 3,
                    "and it opened its own turn"
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    /** Arrival, then the first detailed scan of the body. */
    private static void surveyedAndScanned(SemanticPipelineHarness harness) {
        harness.journal(loadGame())
                .journal(ObservationCaptureMode.LIVE, jump())
                .closeBatch();
        harness.journal(ObservationCaptureMode.LIVE, scan(1833.95371))
                .closeBatch();
    }

    private static String loadGame() {
        return """
                {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """;
    }

    private static String jump() {
        return """
                {"timestamp":"2026-07-30T10:00:01Z","event":"FSDJump",
                 "StarSystem":"Schieni GG-A c3-64","SystemAddress":17658387800858,
                 "Body":"Schieni GG-A c3-64","BodyID":0,"BodyType":"Star",
                 "JumpDist":2.839,"FuelUsed":0.001857,"FuelLevel":123.360168}
                """;
    }

    /**
     * The body's detailed scan, at the distance it was at when read.
     *
     * <p>Everything but the distance is fixed, which is exactly the shape the
     * game re-emits after a survey.</p>
     */
    private static String scan(double distance) {
        return "{\"timestamp\":\"2026-07-30T10:01:59Z\",\"event\":\"Scan\","
                + "\"ScanType\":\"Detailed\","
                + "\"StarSystem\":\"Schieni GG-A c3-64\","
                + "\"SystemAddress\":17658387800858,\"BodyID\":5,"
                + "\"BodyName\":\"Schieni GG-A c3-64 3\","
                + "\"PlanetClass\":\"High metal content body\","
                + "\"Atmosphere\":\"thin nitrogen atmosphere\","
                + "\"Landable\":true,\"TerraformState\":\"\","
                + "\"Volcanism\":\"\",\"WasDiscovered\":false,"
                + "\"WasMapped\":false,\"WasFootfalled\":false,"
                + "\"MeanAnomaly\":339.821389,"
                + "\"DistanceFromArrivalLS\":" + distance + "}";
    }

    /** The same body, now reported as mapped: a different result. */
    private static String mappedScan() {
        return "{\"timestamp\":\"2026-07-30T10:06:09Z\",\"event\":\"Scan\","
                + "\"ScanType\":\"Detailed\","
                + "\"StarSystem\":\"Schieni GG-A c3-64\","
                + "\"SystemAddress\":17658387800858,\"BodyID\":5,"
                + "\"BodyName\":\"Schieni GG-A c3-64 3\","
                + "\"PlanetClass\":\"High metal content body\","
                + "\"Atmosphere\":\"thin nitrogen atmosphere\","
                + "\"Landable\":true,\"TerraformState\":\"\","
                + "\"Volcanism\":\"\",\"WasDiscovered\":false,"
                + "\"WasMapped\":true,\"WasFootfalled\":false,"
                + "\"DistanceFromArrivalLS\":1833.953549}";
    }

    private static String approach() {
        return """
                {"timestamp":"2026-07-30T10:01:00Z","event":"ApproachBody",
                 "StarSystem":"Schieni GG-A c3-64","SystemAddress":17658387800858,
                 "Body":"Schieni GG-A c3-64 3","BodyID":5}
                """;
    }

    private static String mappingComplete() {
        return """
                {"timestamp":"2026-07-30T10:06:09Z","event":"SAAScanComplete",
                 "BodyName":"Schieni GG-A c3-64 3","SystemAddress":17658387800858,
                 "BodyID":5,"ProbesUsed":4,"EfficiencyTarget":6}
                """;
    }
}
