package kairon.observer.decision;

import kairon.behavior.normalize.NormalizedEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The surface scanner reports findings too.
 *
 * <p>{@code SAASignalsFound} was structural for the behaviour graph and
 * context-only for the observer, so a body first read by the surface scanner
 * produced an occurrence and no event: the finding reached Kairon's memory and
 * never reached the Commander. A changed reading after a system scan had the
 * same fate — a second occurrence, no second turn, and the new set visible only
 * as a background count on some unrelated later turn.</p>
 *
 * <p>Both instruments now report the same domain fact under the same name. What
 * distinguishes two readings is what they found, which is in the event's own
 * signals, not which scanner produced them.</p>
 *
 * <p>Everything here runs the production parser, projector and behaviour graph
 * against isolated temporary storage. The provider is a stub that cannot
 * influence what is built.</p>
 */
final class SurfaceSurveyTriggerTest {

    /** B1: a surface survey alone tells the model what it found. */
    @Test
    void aSurveyIsTheFirstToReportAndOpensItsOwnTurn(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            pipeline.settle();
            int beforeSignals = pipeline.modelInputs().size();

            pipeline.journal(saa("2026-07-30T10:01:00Z", BIO_1));
            pipeline.settle();

            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    )
            );
            assertEquals(
                    beforeSignals + 1,
                    pipeline.modelInputs().size(),
                    "the finding opened a turn of its own"
            );
            assertEquals(
                    """
                    {"events":[{"id":1,"event":"A surface area analysis scan reported \
                    signal data for a planet or rings.",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "signals":[{"type":"BIOLOGICAL","count":1}]}],\
                    "trajectory":{"recent":["SYSTEM_ENTERED"]}}""",
                    lastUserMessage(pipeline)
            );
        }
    }

    /** B2: and the system scan confirming it says nothing further. */
    @Test
    void aConfirmingSystemScanInALaterBatchOpensNoTurn(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            pipeline.journal(fss("2026-07-30T10:01:00Z", BIO_1));
            pipeline.settle();
            int afterFirstReading = pipeline.modelInputs().size();

            pipeline.journal(saa("2026-07-30T10:02:00Z", BIO_1));
            pipeline.settle();

            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    ),
                    pipeline.episodeTypes()
            );
            assertEquals(
                    afterFirstReading,
                    pipeline.modelInputs().size(),
                    "one finding, one turn"
            );
        }
    }

    /** B4: a changed reading inside an open batch is a second event. */
    @Test
    void aChangedSurveyInsideAnOpenBatchKeepsSourceOrder(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(fss("2026-07-30T10:01:00Z", BIO_1));
            pipeline.journal(saa("2026-07-30T10:01:01Z", BIO_1_GEO_2));
            pipeline.settleProjection();
            List<NormalizedEventType> run = pipeline.episodeTypes();
            pipeline.replayExhausted("2026-07-30T10:01:02Z");
            pipeline.settle();

            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    run
            );
            assertEquals(1, pipeline.modelInputs().size());
            assertEquals(
                    """
                    {"events":[{"id":1,"event":"A ship jumped from one star system to another.",\
                    "system":"Schieni","fuelUsed":0.4,"distanceLy":8.5},\
                    {"id":2,"event":"A full spectrum system scan reported signal data for a body.",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "signals":[{"type":"BIOLOGICAL","count":1}]},\
                    {"id":3,"event":"A surface area analysis scan reported signal data for a planet or rings.",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "signals":[{"type":"BIOLOGICAL","count":1},\
                    {"type":"GEOLOGICAL","count":2}]}],\
                    "changes":[{"eventId":1,"subject":"commander",\
                    "kind":"ESTABLISHED","fields":{"presence":\
                    {"after":"SHIP"}}},{"eventId":1,"subject":"vehicle",\
                    "kind":"ESTABLISHED","fields":{"kind":{"after":"SHIP"}}}],\
                    "context":{"navigation":{"flightMode":"SUPERCRUISE"}}}""",
                    lastUserMessage(pipeline),
                    "both findings, in journal order, ids 1..n"
            );
        }
    }

    /** B6: a system scan that finds more than the survey did is news. */
    @Test
    void aChangedSystemScanAfterASurveyIsASecondFinding(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            pipeline.journal(saa("2026-07-30T10:01:00Z", BIO_1));
            pipeline.settle();
            int afterSurvey = pipeline.modelInputs().size();

            pipeline.journal(fss("2026-07-30T10:02:00Z", BIO_2));
            pipeline.settle();

            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.SAA_SIGNALS_FOUND,
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    ),
                    pipeline.episodeTypes()
            );
            assertEquals(afterSurvey + 1, pipeline.modelInputs().size());
            assertEquals(
                    """
                    {"events":[{"id":1,"event":"A full spectrum system scan reported signal data for a body.",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "signals":[{"type":"BIOLOGICAL","count":2}]}],\
                    "trajectory":{"recent":["SYSTEM_ENTERED",\
                    "BODY_SIGNALS_FOUND"]}}""",
                    lastUserMessage(pipeline)
            );
        }
    }

    /** B7: a survey that found nothing is not a finding. */
    @Test
    void anEmptySurveyIsNotAFindingAndClearsNothing(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            // A selected body, so the published counts are readable.
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:30Z",
                     "event":"ApproachBody","StarSystem":"Schieni",
                     "SystemAddress":23155,"Body":"Schieni 4 a","BodyID":20}
                    """);
            pipeline.journal(fss("2026-07-30T10:01:00Z", BIO_1));
            pipeline.settle();
            int afterFirstReading = pipeline.modelInputs().size();

            pipeline.journal(saa("2026-07-30T10:02:00Z", ""));
            pipeline.settle();
            assertEquals(
                    1,
                    pipeline.capturedProjections().getLast()
                            .currentState().biologicalSignalCount(),
                    "a reading that mentions nothing retracts nothing"
            );

            pipeline.journal(saa("2026-07-30T10:02:01Z", BIO_ZERO));
            pipeline.settle();

            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    )
            );
            assertEquals(
                    afterFirstReading,
                    pipeline.modelInputs().size(),
                    "finding nothing is not a finding"
            );
            assertEquals(
                    1,
                    pipeline.capturedProjections().getLast()
                            .currentState().biologicalSignalCount(),
                    "a category listed at zero retracts nothing either: the "
                            + "game counts what is there and has no way of "
                            + "saying that what was counted is gone"
            );
        }
    }

    /** B8: the completed survey and its result are two facts, in order. */
    @Test
    void theCompletedSurveyAndItsResultBothReachTheModel(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:01:00Z",
                     "event":"SAAScanComplete","BodyName":"Schieni 4 a",
                     "SystemAddress":23155,"BodyID":20,"ProbesUsed":2,
                     "EfficiencyTarget":2}
                    """);
            pipeline.journal(saa("2026-07-30T10:01:00Z", BIO_1_GEO_2));
            pipeline.settleProjection();
            List<NormalizedEventType> run = pipeline.episodeTypes();
            pipeline.replayExhausted("2026-07-30T10:01:01Z");
            pipeline.settle();

            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.SAA_SCAN_COMPLETE,
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    run,
                    "the action and its result stay separate, in order"
            );
            assertEquals(
                    List.of(
                            "SYSTEM_JUMP",
                            "BODY_MAPPING_COMPLETED",
                            "BODY_SIGNALS_FOUND"
                    ),
                    pipeline.modelFacingKinds()
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private static final String BIO_1 =
            "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}";
    private static final String BIO_2 =
            "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":2}";
    private static final String BIO_ZERO =
            "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":0}";
    private static final String BIO_1_GEO_2 = BIO_1
            + ",{\"Type\":\"$SAA_SignalType_Geological;\",\"Count\":2}";

    /**
     * A pipeline whose batch closes on every single trigger.
     *
     * <p>How a mid-session batch boundary is reached without ending the replay:
     * replay exhaustion also completes the graph episode, which would confound
     * "the observer declined" with "there was no visit". Nothing else about the
     * pipeline changes.</p>
     */
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
                 "StarSystem":"Schieni","SystemAddress":23155,
                 "JumpDist":8.5,"FuelUsed":0.4,"FuelLevel":30.2}
                """);
        pipeline.settleProjection();
    }

    private static String fss(String timestamp, String signals) {
        return record(timestamp, "FSSBodySignals", signals);
    }

    private static String saa(String timestamp, String signals) {
        return record(timestamp, "SAASignalsFound", signals);
    }

    private static String record(
            String timestamp,
            String eventName,
            String signals
    ) {
        return "{\"timestamp\":\"" + timestamp + "\",\"event\":\""
                + eventName + "\",\"StarSystem\":\"Schieni\","
                + "\"SystemAddress\":23155,\"BodyID\":20,"
                + "\"BodyName\":\"Schieni 4 a\",\"Signals\":["
                + signals + "]}";
    }

    private static String lastUserMessage(
            DecisionProductionPipeline pipeline
    ) {
        String userMessage = pipeline.modelInputs().getLast().userMessage();
        return userMessage.substring(userMessage.indexOf('{'));
    }
}
