package kairon.observer.decision;

import kairon.behavior.normalize.NormalizedEventType;
import kairon.llm.LlmClient;
import kairon.behavior.context.BodyDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A count of zero is not the disappearance of a signal.
 *
 * <p>The game reports a signal by counting it. It has no way of saying that a
 * signal previously counted is gone, and a category listed at zero — or below
 * it — is not that statement: it is an instrument reporting nothing under a
 * heading. Treating it as a retraction erased a count an earlier scanner had
 * established, on the strength of a reading that established nothing.</p>
 *
 * <p>One normalization decides it, {@code BodySurveyFacts.normalizedSignalCounts},
 * and everything downstream shares it: the canonical merge, the signature the
 * graph deduplicates on, the observer's novelty memory and the signals the model
 * is shown. Nothing anywhere sees a count below one.</p>
 */
final class NonPositiveSignalCountTest {

    /** B1: zero does not clear a known biological count. */
    @Test
    void zeroDoesNotClearAKnownCount(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            pipeline.journal(signals("10:01:00Z", "FSSBodySignals", BIO_1));
            pipeline.settle();
            int afterFinding = pipeline.modelInputs().size();

            pipeline.journal(signals("10:02:00Z", "SAASignalsFound", BIO_0));
            pipeline.settle();

            assertEquals(1, biological(pipeline), "the count still stands");
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    "reporting nothing is not a finding"
            );
            assertNull(
                    pipeline.edge(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    "and it teaches the graph nothing"
            );
            assertEquals(afterFinding, pipeline.modelInputs().size());

            pipeline.journal(signals("10:03:00Z", "FSSBodySignals", BIO_1));
            pipeline.settle();
            assertEquals(
                    afterFinding,
                    pipeline.modelInputs().size(),
                    "the novelty memory still holds the reading it was told "
                            + "about, not the zero"
            );
        }
    }

    /** B2: neither does a negative one. */
    @Test
    void aNegativeCountDoesNotClearAKnownCount(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            pipeline.journal(signals("10:01:00Z", "FSSBodySignals", BIO_2));
            pipeline.settle();
            int afterFinding = pipeline.modelInputs().size();

            pipeline.journal(
                    signals("10:02:00Z", "SAASignalsFound", BIO_MINUS_1)
            );
            pipeline.settle();

            assertEquals(2, biological(pipeline));
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    )
            );
            assertEquals(afterFinding, pipeline.modelInputs().size());
        }
    }

    /** B3: an empty list clears nothing either. */
    @Test
    void anEmptyListDoesNotClearKnownCounts(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            pipeline.journal(
                    signals("10:01:00Z", "FSSBodySignals", BIO_1 + "," + GEO_2)
            );
            pipeline.settle();
            int afterFinding = pipeline.modelInputs().size();

            pipeline.journal(signals("10:02:00Z", "SAASignalsFound", ""));
            pipeline.settle();

            BodyDetail state = body(pipeline);
            assertEquals(1, state.biologicalSignalCount());
            assertEquals(2, state.geologicalSignalCount());
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    )
            );
            assertEquals(afterFinding, pipeline.modelInputs().size());
        }
    }

    /** B4: a category the reading never mentions keeps its count. */
    @Test
    void anUnmentionedCategoryKeepsItsCount(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            pipeline.journal(signals("10:01:00Z", "FSSBodySignals", BIO_1));
            pipeline.settle();
            int afterFirstFinding = pipeline.modelInputs().size();

            pipeline.journal(signals("10:02:00Z", "SAASignalsFound", GEO_2));
            pipeline.settle();

            BodyDetail state = body(pipeline);
            assertEquals(
                    1,
                    state.biologicalSignalCount(),
                    "silence about biology is not a retraction of it"
            );
            assertEquals(2, state.geologicalSignalCount());
            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    "a different set is a different finding"
            );
            assertEquals(
                    afterFirstFinding + 1,
                    pipeline.modelInputs().size()
            );
            assertEquals(
                    """
                    {"events":[{"event":"A surface area analysis scan reported \
                    signal data for a planet or rings.",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "geologicalSignals":2}],\
                    "context":{"body":{"biologicalSignals":1}},\
                    "trajectory":{"recent":["A ship jumped from one star system to another.","A ship in supercruise \
                    came within a body's orbital-cruise zone.",\
                    "A full spectrum system scan reported signal data for a body."]}}""",
                    lastUserMessage(pipeline),
                    "the event states what this reading found; what the "
                            + "earlier one found is context"
            );
        }
    }

    /** B5: only the positive half of a mixed reading is anything at all. */
    @Test
    void onlyThePositiveHalfOfAMixedReadingCounts(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            pipeline.journal(signals("10:01:00Z", "FSSBodySignals", BIO_1));
            pipeline.settle();
            int afterFirstFinding = pipeline.modelInputs().size();

            pipeline.journal(signals(
                    "10:02:00Z",
                    "SAASignalsFound",
                    BIO_0 + "," + GEO_2
            ));
            pipeline.settle();

            BodyDetail state = body(pipeline);
            assertEquals(1, state.biologicalSignalCount());
            assertEquals(2, state.geologicalSignalCount());
            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    "the geological finding is what makes this a finding"
            );
            assertEquals(
                    afterFirstFinding + 1,
                    pipeline.modelInputs().size()
            );
            assertEquals(
                    """
                    {"events":[{"event":"A surface area analysis scan reported \
                    signal data for a planet or rings.",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "geologicalSignals":2}],\
                    "context":{"body":{"biologicalSignals":1}},\
                    "trajectory":{"recent":["A ship jumped from one star system to another.","A ship in supercruise \
                    came within a body's orbital-cruise zone.",\
                    "A full spectrum system scan reported signal data for a body."]}}""",
                    lastUserMessage(pipeline),
                    "the model is never told a signal is at zero"
            );
        }
    }

    /** B6: a reading of nothing but zeros, on a body nothing had established. */
    @Test
    void aReadingOfNothingButZerosIsNotAFinding(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            int beforeReading = pipeline.modelInputs().size();

            pipeline.journal(signals(
                    "10:01:00Z",
                    "SAASignalsFound",
                    BIO_0 + "," + GEO_0
            ));
            pipeline.settle();

            BodyDetail state = body(pipeline);
            assertNull(
                    state.biologicalSignalCount(),
                    "a reading that counted nothing established nothing"
            );
            assertNull(state.geologicalSignalCount());
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    )
            );
            assertEquals(beforeReading, pipeline.modelInputs().size());

            pipeline.journal(signals("10:02:00Z", "SAASignalsFound", BIO_2));
            pipeline.settle();
            assertEquals(
                    2,
                    biological(pipeline),
                    "and a later reading that found something still counts"
            );
            assertEquals(beforeReading + 1, pipeline.modelInputs().size());
        }
    }

    /** B7: an unrecognised category at zero establishes no category. */
    @Test
    void anUnknownCategoryAtZeroEstablishesNothing(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            int beforeReading = pipeline.modelInputs().size();

            pipeline.journal(signals(
                    "10:01:00Z",
                    "SAASignalsFound",
                    "{\"Type\":\"$SAA_SignalType_Xenological;\","
                            + "\"Type_Localised\":\"Xenological\",\"Count\":0}"
            ));
            pipeline.settle();

            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    )
            );
            assertEquals(beforeReading, pipeline.modelInputs().size());
            assertFalse(
                    everySentDocument(pipeline).contains("OTHER"),
                    "no category is invented out of a zero"
            );
            assertFalse(everySentDocument(pipeline).contains("Xenological"));
        }
    }

    /** B8: the two instruments answer identically. */
    @Test
    void bothScannersTreatZeroTheSameWay(@TempDir Path directory)
            throws Exception {
        assertEquals(
                zeroOutcome(directory.resolve("fss"), "FSSBodySignals"),
                zeroOutcome(directory.resolve("saa"), "SAASignalsFound"),
                "which instrument reported the zero changes nothing"
        );
    }

    /**
     * What a zero does, whichever instrument reports it.
     *
     * <p>Deliberately the whole outcome rather than a single number: the
     * canonical counts, the structural record and what the model was shown, so
     * that the two instruments are compared on every consequence at once.</p>
     *
     * <p>The one thing that legitimately differs is each record's own account
     * of what it is — a system scan and a surface survey say so in their own
     * words — so the document is compared with that sentence taken out. What a
     * zero does is the claim; which instrument said it is not.</p>
     */
    private static String zeroOutcome(Path directory, String eventName)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            pipeline.journal(signals("10:01:00Z", eventName, BIO_1));
            pipeline.settle();
            pipeline.journal(signals("10:02:00Z", eventName, BIO_0));
            pipeline.settle();

            BodyDetail state = body(pipeline);
            return "bio=" + state.biologicalSignalCount()
                    + " geo=" + state.geologicalSignalCount()
                    + " occurrences=" + pipeline.episodeTypes().size()
                    + " modelFacing=" + pipeline.modelFacingKinds()
                    + " document=" + withoutTheInstrumentsOwnWords(
                            lastUserMessage(pipeline));
        }
    }

    /** The document with each event's self-description replaced. */
    private static String withoutTheInstrumentsOwnWords(String document) {
        return document.replaceAll(
                "\"event\":\"[^\"]*\"",
                "\"event\":\"<said>\""
        );
    }

    // ------------------------------------------------------------- fixtures

    private static final String BIO_1 =
            "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}";
    private static final String BIO_2 =
            "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":2}";
    private static final String BIO_0 =
            "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":0}";
    private static final String BIO_MINUS_1 =
            "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":-1}";
    private static final String GEO_2 =
            "{\"Type\":\"$SAA_SignalType_Geological;\",\"Count\":2}";
    private static final String GEO_0 =
            "{\"Type\":\"$SAA_SignalType_Geological;\",\"Count\":0}";

    private static DecisionProductionPipeline perTrigger(Path directory) {
        return new DecisionProductionPipeline(
                directory,
                new DecisionTurnPolicy(1, 16_000)
        );
    }

    private static void arrived(DecisionProductionPipeline pipeline)
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
        pipeline.settle();
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

    /** What the current-system registry has established about the body. */
    private static BodyDetail body(DecisionProductionPipeline pipeline) {
        return pipeline.establishedBody(23155L, 20L);
    }

    private static Integer biological(DecisionProductionPipeline pipeline) {
        return body(pipeline).biologicalSignalCount();
    }

    private static String lastUserMessage(
            DecisionProductionPipeline pipeline
    ) {
        String message = pipeline.modelInputs().getLast().userMessage();
        return message.substring(message.indexOf('{'));
    }

    private static String everySentDocument(
            DecisionProductionPipeline pipeline
    ) {
        StringBuilder all = new StringBuilder();
        for (LlmClient.ModelInput input : pipeline.modelInputs()) {
            all.append(input.userMessage());
        }
        return all.toString();
    }
}
