package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What a scanner found on a body, said once.
 *
 * <p>Two instruments report the same fact. The system scanner reports it while
 * the Commander is still in supercruise, and the surface survey confirms it
 * afterwards, unchanged. That is one finding told twice, and only the first of
 * them is news — but a surface survey that reports something the system scan
 * did not is a second finding, and both of them reach the model under the same
 * name, because what the Commander learned is what is on the body rather than
 * which instrument said so.</p>
 *
 * <p>The whole reported set travels, not only the two categories the canonical
 * snapshot happens to publish. A Thargoid signal used to be discarded on the
 * way in.</p>
 *
 * <p>Everything here runs the production parser, projector and behaviour graph
 * against isolated temporary storage. The provider is a stub that cannot
 * influence what is built.</p>
 */
final class BodySignalsEventTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /** D1: one biological signal, from the system scanner. */
    @Test
    void aBiologicalReadingBecomesOneModelFacingEvent(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\","
                            + "\"Type_Localised\":\"Biological\",\"Count\":1}"
            ));
            pipeline.settleProjection();

            assertEquals(
                    """
                    {"event":"A full spectrum system scan reported signal data for a body.",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "signals":[{"type":"BIOLOGICAL","count":1}]}""",
                    eventJson(pipeline, "FSSBodySignals")
            );
            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    )
            );
        }
    }

    /** D2: and one geological signal is the same shape. */
    @Test
    void aGeologicalReadingBecomesOneModelFacingEvent(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Geological;\",\"Count\":2}"
            ));
            pipeline.settleProjection();

            assertEquals(
                    """
                    {"event":"A full spectrum system scan reported signal data for a body.",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "signals":[{"type":"GEOLOGICAL","count":2}]}""",
                    eventJson(pipeline, "FSSBodySignals")
            );
        }
    }

    /** D3: every category the contract names survives the journey. */
    @Test
    void noKnownSignalCategoryIsDiscarded(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Thargoid;\",\"Count\":4},"
                            + "{\"Type\":\"$SAA_SignalType_Geological;\","
                            + "\"Count\":2},"
                            + "{\"Type\":\"$SAA_SignalType_Human;\","
                            + "\"Count\":3},"
                            + "{\"Type\":\"$SAA_SignalType_Biological;\","
                            + "\"Count\":1}"
            ));
            pipeline.settleProjection();

            assertEquals(
                    """
                    {"event":"A full spectrum system scan reported signal data for a body.",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "signals":[{"type":"BIOLOGICAL","count":1},\
                    {"type":"GEOLOGICAL","count":2},\
                    {"type":"HUMAN","count":3},\
                    {"type":"THARGOID","count":4}]}""",
                    eventJson(pipeline, "FSSBodySignals"),
                    "a fixed order, so one reading always reads the same way"
            );
        }
    }

    /** D4: an unrecognised category keeps its name and loses its symbol. */
    @Test
    void anUnknownCategoryIsReportedAsOtherWithItsLabel(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Guardian;\","
                            + "\"Type_Localised\":\"Guardian\",\"Count\":1}"
            ));
            pipeline.settleProjection();

            String serialized = requestFor(pipeline, "FSSBodySignals")
                    .toString();
            assertEquals(
                    """
                    {"event":"A full spectrum system scan reported signal data for a body.",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "signals":[{"type":"OTHER","label":"Guardian",\
                    "count":1}]}""",
                    eventJson(pipeline, "FSSBodySignals")
            );
            assertFalse(
                    serialized.contains("SAA_SignalType"),
                    "the game's own identifier never reaches the model"
            );
        }
    }

    /** D5: finding nothing is not a finding, and retracts nothing. */
    @Test
    void anEmptyReadingIsNotAFindingAndClearsNothing(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:01:00Z",
                     "event":"ApproachBody","StarSystem":"Schieni",
                     "SystemAddress":23155,"Body":"Schieni 4 a","BodyID":20}
                    """);
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:01Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":3}"
            ));
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:02Z",
                    20,
                    "Schieni 4 a",
                    ""
            ));
            pipeline.settleProjection();

            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    )
            );
            assertEquals(
                    3,
                    pipeline.capturedProjections().getLast()
                            .currentState().biologicalSignalCount(),
                    "silence about a category is not a retraction of it"
            );
            turn(pipeline, "2026-07-30T10:01:03Z");
            assertEquals(
                    List.of(
                            "SYSTEM_JUMP",
                            "BODY_APPROACHED",
                            "BODY_SIGNALS_FOUND"
                    ),
                    pipeline.modelFacingKinds()
            );
        }
    }

    /** D6: the same reading again is the same reading. */
    @Test
    void anIdenticalRepeatIsNotASecondFinding(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}"
            ));
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:05Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}"
            ));
            turn(pipeline, "2026-07-30T10:01:06Z");

            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    )
            );
            assertEquals(
                    List.of("SYSTEM_JUMP", "BODY_SIGNALS_FOUND"),
                    pipeline.modelFacingKinds()
            );
        }
    }

    /** D7: and so is the surface survey confirming it. */
    @Test
    void aSurfaceSurveyConfirmingTheSameSetIsNotASecondFinding(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}"
            ));
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:02:00Z",
                     "event":"SAAScanComplete","BodyName":"Schieni 4 a",
                     "SystemAddress":23155,"BodyID":20,"ProbesUsed":2,
                     "EfficiencyTarget":2}
                    """);
            pipeline.journal(saaSignals(
                    "2026-07-30T10:02:01Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}"
            ));
            pipeline.settleProjection();
            List<NormalizedEventType> run = pipeline.episodeTypes();
            turn(pipeline, "2026-07-30T10:02:02Z");

            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                            NormalizedEventType.SAA_SCAN_COMPLETE
                    ),
                    run,
                    "the completed survey stays; only its restated result goes"
            );
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    )
            );
            assertEquals(
                    List.of(
                            "SYSTEM_JUMP",
                            "BODY_SIGNALS_FOUND",
                            "BODY_MAPPING_COMPLETED"
                    ),
                    pipeline.modelFacingKinds()
            );
        }
    }

    /**
     * D8: a survey that finds more than the system scan did is news.
     *
     * <p>Each reading closes its own batch, which is how the game
     * delivers them: the survey happens minutes after the system scan.
     * The second finding used to reach the graph and stop there.</p>
     */
    @Test
    void aSurfaceSurveyReportingMoreIsASecondFinding(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     oneTurnPerTrigger(directory)) {
            arrived(pipeline);
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}"
            ));
            pipeline.settle();
            int afterFirstReading = pipeline.modelInputs().size();

            pipeline.journal(saaSignals(
                    "2026-07-30T10:02:00Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1},"
                            + "{\"Type\":\"$SAA_SignalType_Geological;\","
                            + "\"Count\":2}"
            ));
            pipeline.settle();
            List<NormalizedEventType> run = pipeline.episodeTypes();

            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    run
            );
            assertEquals(
                    afterFirstReading + 1,
                    pipeline.modelInputs().size(),
                    "the changed reading opens a turn of its own"
            );
            assertEquals(
                    List.of(
                            "SYSTEM_JUMP",
                            "BODY_SIGNALS_FOUND",
                            "BODY_SIGNALS_FOUND"
                    ),
                    pipeline.modelFacingKinds()
            );
            assertEquals(
                    """
                    {"events":[{"event":"A surface area analysis scan reported \
                    signal data for a planet or rings.",\
                    "body":"Schieni 4 a","system":"Schieni",\
                    "signals":[{"type":"BIOLOGICAL","count":1},\
                    {"type":"GEOLOGICAL","count":2}]}],\
                    "trajectory":{"recent":["A ship jumped from one star system to another.",\
                    "A full spectrum system scan reported signal data for a body."]}}""",
                    lastUserMessage(pipeline),
                    "the second finding is factual and complete"
            );
            // Both instruments report the same kind of fact, and both are
            // still one kind — BODY_SIGNALS_FOUND, above. Remembered, each
            // says which instrument said it, because that is what its own
            // event says when it happens: a trajectory that spoke for both at
            // once would be the one place in the request where a finding loses
            // its source.
            assertEquals(
                    "A surface area analysis scan reported signal data for a "
                            + "planet or rings.",
                    DecisionTrajectoryDescriptions.descriptionOf(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    )
            );
            assertEquals(
                    "A full spectrum system scan reported signal data for a "
                            + "body.",
                    DecisionTrajectoryDescriptions.descriptionOf(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    )
            );
        }
    }

    /**
     * D9: the survey reports its own finding; the confirmation does not.
     *
     * <p>Whichever instrument speaks first tells the model what is on the
     * body. The surface survey used to open no turn at all, so a body
     * first read by the surface scanner reached the model as nothing.</p>
     */
    @Test
    void theSystemScanConfirmingASurveyIsNotASecondFinding(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(saaSignals(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}"
            ));
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:05Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}"
            ));
            turn(pipeline, "2026-07-30T10:01:06Z");

            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    )
            );
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    )
            );
            assertEquals(
                    List.of("SYSTEM_JUMP", "BODY_SIGNALS_FOUND"),
                    pipeline.modelFacingKinds(),
                    "the survey reports the finding; the system scan "
                            + "restating it opens no second turn"
            );
        }
    }

    /** D10: two bodies with the same reading are two findings. */
    @Test
    void twoBodiesWithTheSameReadingAreNeverMerged(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}"
            ));
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:01Z",
                    21,
                    "Schieni 4 b",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}"
            ));
            turn(pipeline, "2026-07-30T10:01:02Z");

            assertEquals(
                    2L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    )
            );
            assertEquals(
                    List.of(
                            "SYSTEM_JUMP",
                            "BODY_SIGNALS_FOUND",
                            "BODY_SIGNALS_FOUND"
                    ),
                    pipeline.modelFacingKinds()
            );
        }
    }

    // -------------------------------------------------- E. order and batching

    /** E1: signals then scan, at one timestamp, stay in that order. */
    @Test
    void signalsBeforeAScanKeepTheirSourceOrder(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}"
            ));
            pipeline.journal(detailedPlanet("2026-07-30T10:01:00Z", 20));
            pipeline.settleProjection();
            List<NormalizedEventType> run = pipeline.episodeTypes();
            turn(pipeline, "2026-07-30T10:01:01Z");

            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                            NormalizedEventType.BODY_SCANNED
                    ),
                    run
            );
            assertEquals(
                    List.of(
                            "SYSTEM_JUMP",
                            "BODY_SIGNALS_FOUND",
                            "BODY_SCANNED"
                    ),
                    pipeline.modelFacingKinds()
            );
        }
    }

    /** E2: and so does the other order. */
    @Test
    void aScanBeforeSignalsKeepsTheOtherOrder(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(detailedPlanet("2026-07-30T10:01:00Z", 20));
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}"
            ));
            pipeline.settleProjection();
            List<NormalizedEventType> run = pipeline.episodeTypes();
            turn(pipeline, "2026-07-30T10:01:01Z");

            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.BODY_SCANNED,
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    ),
                    run
            );
            assertEquals(
                    List.of(
                            "SYSTEM_JUMP",
                            "BODY_SCANNED",
                            "BODY_SIGNALS_FOUND"
                    ),
                    pipeline.modelFacingKinds()
            );
        }
    }

    /**
     * E3: one batch, local ids in source order, and no turn about nothing.
     *
     * <p>The second half is the one that matters: two of these four records
     * restate what the first two already said, and a batch that emptied out
     * after they were declined would be a provider call with nothing in it.
     * Admission happens before the batch, so there is nothing to empty.</p>
     */
    @Test
    void oneBatchNumbersItsEventsInSourceOrder(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}"
            ));
            pipeline.journal(detailedPlanet("2026-07-30T10:01:00Z", 20));
            pipeline.journal(fssSignals(
                    "2026-07-30T10:01:01Z",
                    20,
                    "Schieni 4 a",
                    "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}"
            ));
            pipeline.journal(detailedPlanet("2026-07-30T10:01:01Z", 20));
            turn(pipeline, "2026-07-30T10:01:02Z");

            assertEquals(
                    List.of(
                            "SYSTEM_JUMP",
                            "BODY_SIGNALS_FOUND",
                            "BODY_SCANNED"
                    ),
                    pipeline.modelFacingKinds()
            );
            assertEquals(
                    List.of(1, 2, 3),
                    localIds(pipeline),
                    "ids run one to n in the order the journal recorded them"
            );
        }
    }

    // ------------------------------------------------------------- fixtures

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
    }

    private static String fssSignals(
            String timestamp,
            int bodyId,
            String bodyName,
            String signals
    ) {
        return signalsRecord(
                timestamp,
                "FSSBodySignals",
                bodyId,
                bodyName,
                signals
        );
    }

    private static String saaSignals(
            String timestamp,
            int bodyId,
            String bodyName,
            String signals
    ) {
        return signalsRecord(
                timestamp,
                "SAASignalsFound",
                bodyId,
                bodyName,
                signals
        );
    }

    private static String signalsRecord(
            String timestamp,
            String eventName,
            int bodyId,
            String bodyName,
            String signals
    ) {
        return "{\"timestamp\":\"" + timestamp + "\",\"event\":\""
                + eventName + "\",\"StarSystem\":\"Schieni\","
                + "\"SystemAddress\":23155,\"BodyID\":" + bodyId + ","
                + "\"BodyName\":\"" + bodyName + "\","
                + "\"Signals\":[" + signals + "]}";
    }

    private static String detailedPlanet(String timestamp, int bodyId) {
        return "{\"timestamp\":\"" + timestamp + "\",\"event\":\"Scan\","
                + "\"ScanType\":\"Detailed\",\"StarSystem\":\"Schieni\","
                + "\"SystemAddress\":23155,\"BodyID\":" + bodyId + ","
                + "\"BodyName\":\"Schieni 4 a\","
                + "\"PlanetClass\":\"Icy body\",\"Landable\":true,"
                + "\"WasDiscovered\":false,\"WasMapped\":false}";
    }

    private static void turn(
            DecisionProductionPipeline pipeline,
            String timestamp
    ) throws Exception {
        pipeline.replayExhausted(timestamp);
        pipeline.settle();
    }

    /**
     * A pipeline whose batch closes on every single trigger.
     *
     * <p>How a mid-session batch boundary is reached without ending the
     * replay: replay exhaustion also completes the graph episode, which
     * would confound "the observer declined" with "there was no visit".
     * Nothing else about the pipeline changes.</p>
     */
    private static DecisionProductionPipeline oneTurnPerTrigger(
            Path directory
    ) {
        return new DecisionProductionPipeline(
                directory,
                new DecisionTurnPolicy(1, 16_000)
        );
    }

    /** The document of the most recent provider call. */
    private static String lastUserMessage(
            DecisionProductionPipeline pipeline
    ) {
        String userMessage = pipeline.modelInputs()
                .getLast()
                .userMessage();
        return userMessage.substring(userMessage.indexOf('{'));
    }

    // -------------------------------------------------------------- reading

    /**
     * Each turn's events by position, one turn after another.
     *
     * <p>The document carries no id, so the position is read off the array —
     * which is what the id always was. A turn restarting at one is a second
     * turn; a turn continuing at two is a second event of the same one.</p>
     */
    private static List<Integer> localIds(DecisionProductionPipeline pipeline) {
        List<Integer> ids = new ArrayList<>();
        for (var input : pipeline.modelInputs()) {
            String userMessage = input.userMessage();
            int position = 0;
            for (JsonNode event
                    : read(userMessage.substring(userMessage.indexOf('{')))
                            .path("events")) {
                assertFalse(event.has("id"), "an event still carries an id");
                ids.add(++position);
            }
        }
        return List.copyOf(ids);
    }

    private String eventJson(
            DecisionProductionPipeline pipeline,
            String payloadSimpleName
    ) {
        return requestFor(pipeline, payloadSimpleName)
                .path("events").get(0).toString();
    }

    private JsonNode requestFor(
            DecisionProductionPipeline pipeline,
            String payloadSimpleName
    ) {
        ProjectedObservation wanted = pipeline.capturedTriggers().stream()
                .filter(projected -> projected.trigger().payload().getClass()
                        .getSimpleName().equals(payloadSimpleName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        payloadSimpleName + " never reached the projection"
                ));
        for (ProjectedObservation trigger : pipeline.capturedTriggers()) {
            if (trigger.busSequence() >= wanted.busSequence()) {
                break;
            }
            pipeline.inputsFor(List.of(trigger));
        }
        return read(serializer.serialize(factory.create(
                pipeline.inputsFor(List.of(wanted))
        )));
    }

    private static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
