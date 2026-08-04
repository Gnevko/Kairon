package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.llm.LlmClient;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A historical scanner result restores what it established and reports nothing.
 *
 * <p>Bootstrap capture is the journal Kairon read on startup: it happened, but
 * nobody was listening. The model is not told about it, and — since these three
 * records are the ones whose recording decides whether a <em>later</em> reading
 * is a finding at all — the graph does not record it either. Otherwise the live
 * reading repeating it would be given to the model with no occurrence of its
 * own, standing in the trajectory after the same finding under another name.</p>
 *
 * <p>What a historical reading established about the body is kept: canonical
 * state is projected before the graph is consulted, so nothing here costs the
 * Commander the facts.</p>
 *
 * <p>Everything runs the production parser, projector, behaviour graph and
 * observer against isolated temporary storage; the provider is a stub.</p>
 */
final class BootstrapScannerResultTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A1: a historical survey, then the system scan that repeats it. */
    @Test
    void aHistoricalSurveyIsRestoredAndTheLiveReadingIsTheFinding(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            int beforeSignals = pipeline.modelInputs().size();
            NormalizedEventType cursorBefore = cursorType(pipeline);

            pipeline.journal(
                    ObservationCaptureMode.BOOTSTRAP,
                    signals("10:01:00Z", "SAASignalsFound", BIO_1)
            );
            pipeline.settle();

            assertEquals(
                    1,
                    canonicalBiological(pipeline),
                    "a historical reading still establishes what it found"
            );
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    "nobody was listening, so nothing was found here"
            );
            assertEquals(
                    cursorBefore,
                    cursorType(pipeline),
                    "and the graph did not move"
            );
            assertEquals(
                    beforeSignals,
                    pipeline.modelInputs().size(),
                    "historical capture is model-silent"
            );

            pipeline.journal(
                    ObservationCaptureMode.LIVE,
                    signals("10:02:00Z", "FSSBodySignals", BIO_1)
            );
            pipeline.settle();

            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    ),
                    "the live reading is the finding, and it is structural"
            );
            assertEquals(
                    NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                    cursorType(pipeline)
            );
            assertEquals(beforeSignals + 1, pipeline.modelInputs().size());
            assertEquals(
                    """
                    {"events":[{"id":1,"kind":"BODY_SIGNALS_FOUND",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "signals":[{"type":"BIOLOGICAL","count":1}]}],\
                    "trajectory":{"recent":["SYSTEM_ENTERED",\
                    "BODY_APPROACHED"]}}""",
                    lastUserMessage(pipeline),
                    "the trajectory carries no finding from the bootstrap"
            );
        }
    }

    /** A2: and the other way round. */
    @Test
    void aHistoricalSystemScanLeavesTheSurveyToBeTheFinding(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            int beforeSignals = pipeline.modelInputs().size();

            pipeline.journal(
                    ObservationCaptureMode.BOOTSTRAP,
                    signals("10:01:00Z", "FSSBodySignals", BIO_1)
            );
            pipeline.settle();
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    )
            );
            assertEquals(beforeSignals, pipeline.modelInputs().size());

            pipeline.journal(
                    ObservationCaptureMode.LIVE,
                    signals("10:02:00Z", "SAASignalsFound", BIO_1)
            );
            pipeline.settle();

            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    )
            );
            assertEquals(beforeSignals + 1, pipeline.modelInputs().size());
            assertEquals(
                    "BODY_SIGNALS_FOUND",
                    pipeline.modelFacingKinds().getLast()
            );
            assertTrue(
                    trajectory(pipeline).stream()
                            .noneMatch("BODY_SIGNALS_FOUND"::equals),
                    "no bootstrap finding stands behind the live one"
            );
        }
    }

    /** A3: the same rule for a detailed scan. */
    @Test
    void aHistoricalDetailedScanRestoresTheBodyAndReportsNothing(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            int beforeScan = pipeline.modelInputs().size();

            pipeline.journal(
                    ObservationCaptureMode.BOOTSTRAP,
                    scan("10:01:00Z")
            );
            pipeline.settle();

            assertEquals(
                    "Icy body",
                    pipeline.capturedProjections().getLast()
                            .currentState().planetClass(),
                    "the body registry is restored from the historical scan"
            );
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.BODY_SCANNED
                    )
            );
            assertEquals(beforeScan, pipeline.modelInputs().size());

            pipeline.journal(ObservationCaptureMode.LIVE, scan("10:02:00Z"));
            pipeline.settle();

            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.BODY_SCANNED
                    ),
                    "the identical live scan is not suppressed by history"
            );
            assertEquals(beforeScan + 1, pipeline.modelInputs().size());
            assertEquals(
                    "BODY_SCANNED",
                    pipeline.modelFacingKinds().getLast()
            );

            pipeline.journal(ObservationCaptureMode.LIVE, scan("10:03:00Z"));
            pipeline.settle();
            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.BODY_SCANNED
                    ),
                    "live deduplication itself is untouched"
            );
            assertEquals(beforeScan + 1, pipeline.modelInputs().size());
        }
    }

    /** A4: a whole historical survey of one body, then the live repeat. */
    @Test
    void aHistoricalSurveyOfOneBodyCostsNothingAndSuppressesNothing(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            // One batch for the whole run, so both live results are shown
            // together and their local ids can be read in order.
            arriving(pipeline);
            pipeline.journal(
                    ObservationCaptureMode.BOOTSTRAP,
                    signals("10:01:00Z", "FSSBodySignals", BIO_1)
            );
            pipeline.journal(
                    ObservationCaptureMode.BOOTSTRAP,
                    scan("10:01:30Z")
            );
            pipeline.settleProjection();

            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.APPROACH_BODY
                    ),
                    pipeline.episodeTypes(),
                    "neither result is structural, so neither is a transition"
            );
            assertEquals(1, canonicalBiological(pipeline));
            assertEquals(
                    "Icy body",
                    pipeline.capturedProjections().getLast()
                            .currentState().planetClass()
            );
            assertEquals(
                    0,
                    pipeline.modelInputs().size(),
                    "and the model was never asked about any of it"
            );

            pipeline.journal(
                    ObservationCaptureMode.LIVE,
                    signals("10:02:00Z", "FSSBodySignals", BIO_1)
            );
            pipeline.journal(ObservationCaptureMode.LIVE, scan("10:02:30Z"));
            pipeline.settleProjection();
            List<NormalizedEventType> recorded = pipeline.episodeTypes();
            pipeline.replayExhausted("2026-07-30T10:03:00Z");
            pipeline.settle();

            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.APPROACH_BODY,
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                            NormalizedEventType.BODY_SCANNED
                    ),
                    recorded,
                    "both live results are structural, in source order"
            );
            assertEquals(
                    List.of(
                            "SYSTEM_JUMP",
                            "BODY_APPROACHED",
                            "BODY_SIGNALS_FOUND",
                            "BODY_SCANNED"
                    ),
                    pipeline.modelFacingKinds()
            );
            assertEquals(
                    List.of(1, 2, 3, 4),
                    localEventIds(pipeline),
                    "local ids are consecutive and follow the journal"
            );
        }
    }

    /**
     * A5: an ordinary structural event is recorded on bootstrap as before.
     *
     * <p>{@code SAAScanComplete} is the surface survey finishing, not its
     * result: it is structural, it is not one of the three records this fix
     * touches, and a historical one is still an occurrence with its own
     * transition. Only the model, as ever, is not told about it.</p>
     */
    @Test
    void anOrdinaryHistoricalStructuralEventIsStillRecorded(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            int beforeMapping = pipeline.modelInputs().size();

            pipeline.journal(ObservationCaptureMode.BOOTSTRAP, """
                    {"timestamp":"2026-07-30T10:01:00Z",
                     "event":"SAAScanComplete","BodyName":"Schieni 4 a",
                     "SystemAddress":23155,"BodyID":20,"ProbesUsed":2,
                     "EfficiencyTarget":2}
                    """);
            pipeline.settle();

            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SCAN_COMPLETE
                    ),
                    "bootstrap recording is unchanged for everything else"
            );
            assertEquals(
                    NormalizedEventType.SAA_SCAN_COMPLETE,
                    cursorType(pipeline)
            );
            assertNotNull(
                    pipeline.edge(
                            NormalizedEventType.APPROACH_BODY,
                            NormalizedEventType.SAA_SCAN_COMPLETE
                    ),
                    "and it still teaches the graph the transition into it"
            );
            assertEquals(
                    beforeMapping,
                    pipeline.modelInputs().size(),
                    "and it is still model-silent, as every bootstrap is"
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private static final String BIO_1 =
            "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}";

    private static DecisionProductionPipeline perTrigger(Path directory) {
        return new DecisionProductionPipeline(
                directory,
                new DecisionTurnPolicy(1, 16_000)
        );
    }

    private static void arrived(DecisionProductionPipeline pipeline)
            throws Exception {
        arriving(pipeline);
        pipeline.settle();
    }

    /** The arrival, with the observer batch left open. */
    private static void arriving(DecisionProductionPipeline pipeline)
            throws Exception {
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"FSDJump",
                 "StarSystem":"Schieni","SystemAddress":23155,"JumpDist":8.5,
                 "FuelUsed":0.4,"FuelLevel":30.2}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:30Z","event":"ApproachBody",
                 "StarSystem":"Schieni","SystemAddress":23155,
                 "Body":"Schieni 4 a","BodyID":20}
                """);
        pipeline.settleProjection();
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

    private static Integer canonicalBiological(
            DecisionProductionPipeline pipeline
    ) {
        return pipeline.capturedProjections().getLast()
                .currentState().biologicalSignalCount();
    }

    private static NormalizedEventType cursorType(
            DecisionProductionPipeline pipeline
    ) {
        return pipeline.cursor().orElseThrow().eventType();
    }

    private static String lastUserMessage(
            DecisionProductionPipeline pipeline
    ) {
        List<LlmClient.ModelInput> inputs = pipeline.modelInputs();
        String message = inputs.getLast().userMessage();
        return message.substring(message.indexOf('{'));
    }

    private static List<String> trajectory(
            DecisionProductionPipeline pipeline
    ) throws Exception {
        List<String> recent = new ArrayList<>();
        JSON.readTree(lastUserMessage(pipeline))
                .path("trajectory")
                .path("recent")
                .forEach(name -> recent.add(name.textValue()));
        return List.copyOf(recent);
    }

    private static List<Integer> localEventIds(
            DecisionProductionPipeline pipeline
    ) throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (LlmClient.ModelInput input : pipeline.modelInputs()) {
            String message = input.userMessage();
            JsonNode document =
                    JSON.readTree(message.substring(message.indexOf('{')));
            document.path("events")
                    .forEach(event -> ids.add(event.path("id").intValue()));
        }
        return List.copyOf(ids);
    }
}
