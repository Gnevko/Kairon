package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.journal.event.exploration.ScanOrganic;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A biological sample says which stage it is, and never how many.
 *
 * <p>The graph counts {@code SCAN_ORGANIC_LOG}, {@code SCAN_ORGANIC_SAMPLE} and
 * {@code SCAN_ORGANIC_ANALYSE} as three structural types, so a body-scoped count
 * of "this event type here" counts analyses, or samples, or logs. The model sees
 * one kind, {@code BIOLOGICAL_SAMPLE}, and under that kind
 * {@code "occurrenceOnBody": 1} on a finished sequence reads as <em>the first
 * biological sample on this body</em> when it means <em>the first analysis</em>.
 * It is therefore not sent at any stage.</p>
 *
 * <p>Not sent, still counted: every case here asserts the internal count through
 * {@link DecisionOccurrenceScope} beside the event that does not carry it, which
 * is the whole of the change — one projection boundary, nothing behind it.</p>
 */
final class SamplingOccurrenceCountTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ORGANISM = "Bacterium Bullaris - Red";

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    // ------------------------------------------------------------- A: START

    @Test
    void theScanThatStartsASequenceCarriesNoCount(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            sample(pipeline);

            JsonNode event = samplingEvents(pipeline).get(0);
            assertEquals(
                    "{\"event\":\"The organic sampling tool logged the first scan of an "
                            + "unfinished sampling sequence.\","
                            + "\"organism\":\"" + ORGANISM + "\","
                            + "\"stage\":\"START\",\"complete\":false}",
                    event.toString()
            );
            assertFalse(event.has("occurrenceOnBody"));
            assertFalse(event.has("step"));
            assertEquals(
                    Integer.valueOf(1),
                    DecisionOccurrenceScope.occurrenceOnBody(scans(pipeline)
                            .get(0)),
                    "the graph counted it; the projection did not send it"
            );
        }
    }

    // ------------------------------------------------------ B: first PROGRESS

    @Test
    void theSecondScanOfASequenceCarriesNoCount(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            sample(pipeline);

            JsonNode event = samplingEvents(pipeline).get(1);
            assertEquals(
                    "{\"event\":\"The organic sampling tool recorded a subsequent scan of an "
                            + "unfinished sampling sequence.\","
                            + "\"organism\":\"" + ORGANISM + "\","
                            + "\"stage\":\"PROGRESS\",\"complete\":false}",
                    event.toString()
            );
            assertFalse(event.has("occurrenceOnBody"));
            assertFalse(event.has("step"));
        }
    }

    // --------------------------------------------------- C: repeated PROGRESS

    /**
     * The case the field could not survive.
     *
     * <p>Two sample scans at one body are internal occurrence 1 and occurrence
     * 2 of {@code SCAN_ORGANIC_SAMPLE}, and both reach the model as the same
     * {@code stage: PROGRESS}. Sending the counter would have made two identical
     * events differ by a number that counts something the model cannot see.</p>
     */
    @Test
    void aRepeatedProgressScanCarriesNoCountEither(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            sample(pipeline);

            List<JsonNode> events = samplingEvents(pipeline);
            String progress = "{\"event\":\"The organic sampling tool recorded a subsequent scan of an "
                    + "unfinished sampling sequence.\","
                    + "\"organism\":\"" + ORGANISM + "\","
                    + "\"stage\":\"PROGRESS\",\"complete\":false}";
            assertEquals(progress, events.get(1).toString());
            assertEquals(
                    progress,
                    events.get(2).toString(),
                    "the repeat is the same event, not a numbered one"
            );
            assertFalse(events.get(2).has("occurrenceOnBody"));

            List<ProjectedObservation> scans = scans(pipeline);
            assertEquals(
                    Integer.valueOf(1),
                    DecisionOccurrenceScope.occurrenceOnBody(scans.get(1))
            );
            assertEquals(
                    Integer.valueOf(2),
                    DecisionOccurrenceScope.occurrenceOnBody(scans.get(2)),
                    "the internal count keeps running while nothing is sent"
            );
        }
    }

    // ------------------------------------------------------------- D: FINAL

    /**
     * The event the correction was reported for.
     *
     * <p>Its internal count is 1 — the first {@code SCAN_ORGANIC_ANALYSE} at
     * this body — and under the shared kind that number reads as the first
     * biological sample on a body where three scans have already happened.</p>
     */
    @Test
    void theScanThatCompletesASequenceCarriesNoCount(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            sample(pipeline);

            JsonNode event = samplingEvents(pipeline).get(3);
            assertEquals(
                    "{\"event\":\"The organic sampling tool recorded the final scan and "
                            + "completed a sampling sequence.\","
                            + "\"organism\":\"" + ORGANISM + "\","
                            + "\"stage\":\"FINAL\",\"complete\":true}",
                    event.toString()
            );
            assertFalse(event.has("occurrenceOnBody"));
            assertFalse(event.has("step"));
            assertEquals(
                    Integer.valueOf(1),
                    DecisionOccurrenceScope.occurrenceOnBody(scans(pipeline)
                            .get(3)),
                    "the first analysis here, which is not the first sample"
            );
        }
    }

    // ----------------------------------------------------------- E: the field

    /**
     * Every other event that has a count still sends it.
     *
     * <p>The suppression is a claim about one kind whose graph occurrences are
     * stage-specific, not a retreat from the field. A second landing at the same
     * body is still the second landing.</p>
     */
    @Test
    void aLandingOnTheSameBodyStillCarriesItsCount(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            travelAndSample(pipeline);

            JsonNode second = eventsOfKind(pipeline, "TOUCHDOWN").get(1);
            assertEquals(2, second.path("occurrenceOnBody").intValue());
            assertEquals(
                    "A ship landed on the surface of a planet or moon.",
                    second.path("event").textValue());
        }
    }

    // ------------------------------------------------- the whole sequence

    /**
     * Log, sample, sample, analyse: four events, no number among them.
     *
     * <p>Asserted beside the graph the same run wrote, because "the count is not
     * sent" is only the correction if the count is still there. The three
     * structural types keep their own occurrences and their own totals.</p>
     */
    @Test
    void theWholeSequenceIsCountFreeWhileTheGraphKeepsCounting(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            sample(pipeline);

            assertEquals(
                    List.of(
                            "{\"event\":\"The organic sampling tool logged the first scan of an "
                                    + "unfinished sampling sequence.\","
                                    + "\"organism\":\"" + ORGANISM + "\","
                                    + "\"stage\":\"START\","
                                    + "\"complete\":false}",
                            "{\"event\":\"The organic sampling tool recorded a subsequent scan of an "
                                    + "unfinished sampling sequence.\","
                                    + "\"organism\":\"" + ORGANISM + "\","
                                    + "\"stage\":\"PROGRESS\","
                                    + "\"complete\":false}",
                            "{\"event\":\"The organic sampling tool recorded a subsequent scan of an "
                                    + "unfinished sampling sequence.\","
                                    + "\"organism\":\"" + ORGANISM + "\","
                                    + "\"stage\":\"PROGRESS\","
                                    + "\"complete\":false}",
                            "{\"event\":\"The organic sampling tool recorded the final scan and "
                                    + "completed a sampling sequence.\","
                                    + "\"organism\":\"" + ORGANISM + "\","
                                    + "\"stage\":\"FINAL\","
                                    + "\"complete\":true}"
                    ),
                    samplingEvents(pipeline).stream()
                            .map(JsonNode::toString)
                            .toList()
            );

            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SCAN_ORGANIC_LOG
                    ),
                    "BIOLOGICAL_SAMPLE_STARTED"
            );
            assertEquals(
                    2L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SCAN_ORGANIC_SAMPLE
                    ),
                    "BIOLOGICAL_SAMPLE_CONTINUED"
            );
            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SCAN_ORGANIC_ANALYSE
                    ),
                    "The organic sampling tool recorded the final scan and completed a sampling sequence."
            );
        }
    }

    // ------------------------------------------------------- the trajectory

    /**
     * The remembered names are untouched by the field the event lost.
     *
     * <p>The trajectory says the stages the current event no longer counts —
     * {@code BIOLOGICAL_SAMPLE_CONTINUED} before the analysis — and the analysis
     * itself is not among them: {@code recent} is what happened before, and the
     * current event is the event.</p>
     */
    @Test
    void theFinalScanKeepsItsTrajectoryAndItsContext(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            travelAndSample(pipeline);

            JsonNode request = requestsPerTrigger(pipeline).getLast();
            assertEquals(
                    "{\"event\":\"The organic sampling tool recorded the final scan and "
                            + "completed a sampling sequence.\","
                            + "\"organism\":\"" + ORGANISM + "\","
                            + "\"stage\":\"FINAL\",\"complete\":true}",
                    request.path("events").get(0).toString()
            );
            assertEquals(
                    List.of(
                            "A ship landed on the surface of a planet or moon.",
                            "The Commander stepped out of a ship or SRV.",
                            "The organic sampling tool recorded a subsequent scan of an unfinished sampling sequence."
                    ),
                    texts(request.path("trajectory").path("recent"))
            );
            assertFalse(
                    texts(request.path("trajectory").path("recent"))
                            .contains("The organic sampling tool recorded the final scan and completed a sampling "
                                    + "sequence."),
                    "the current event is not one of its own predecessors"
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    /** A surveyed, landable body with one biological signal, in a known ship. */
    private static void arrive(DecisionProductionPipeline pipeline) {
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"Location",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "Docked":false}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:02Z","event":"Scan",
                 "SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni GG-A c3-84 4 a",
                 "PlanetClass":"Icy body","Landable":true,
                 "WasDiscovered":false,"WasMapped":false,"WasFootfalled":false,
                 "DistanceFromArrivalLS":1081.453145}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:03Z","event":"SAASignalsFound",
                 "SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni GG-A c3-84 4 a",
                 "Signals":[{"Type":"$SAA_SignalType_Biological;",
                 "Type_Localised":"Biological","Count":1}]}
                """);
    }

    /** Land, get out, and take the whole sequence at one body. */
    private static void sample(DecisionProductionPipeline pipeline)
            throws Exception {
        arrive(pipeline);
        pipeline.journal(touchdown("2026-07-30T10:01:00Z"));
        pipeline.journal(disembark("2026-07-30T10:02:00Z"));
        pipeline.journal(scanOrganic("2026-07-30T10:03:00Z", "Log"));
        pipeline.journal(scanOrganic("2026-07-30T10:04:00Z", "Sample"));
        pipeline.journal(scanOrganic("2026-07-30T10:05:00Z", "Sample"));
        pipeline.journal(scanOrganic("2026-07-30T10:06:00Z", "Analyse"));
        pipeline.settleProjection();
    }

    /**
     * The same sequence with a flight between the log and the sample.
     *
     * <p>What the audited run did: log a plant, ride back, lift off, land
     * further along the same body, get out again, and finish there. It is also
     * what puts a landing, a disembark and a sample scan in front of the final
     * analysis.</p>
     */
    private static void travelAndSample(DecisionProductionPipeline pipeline)
            throws Exception {
        arrive(pipeline);
        pipeline.journal(touchdown("2026-07-30T10:01:00Z"));
        pipeline.journal(disembark("2026-07-30T10:02:00Z"));
        pipeline.journal(scanOrganic("2026-07-30T10:03:00Z", "Log"));
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:04:00Z","event":"Embark",
                 "SRV":true,"ID":10,"StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155,"Body":"Schieni GG-A c3-84 4 a",
                 "BodyID":20,"OnStation":false,"OnPlanet":true}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:05:00Z","event":"Liftoff",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "PlayerControlled":true,"OnStation":false,"OnPlanet":true}
                """);
        pipeline.journal(touchdown("2026-07-30T10:06:00Z"));
        pipeline.journal(disembark("2026-07-30T10:07:00Z"));
        pipeline.journal(scanOrganic("2026-07-30T10:08:00Z", "Sample"));
        pipeline.journal(scanOrganic("2026-07-30T10:09:00Z", "Analyse"));
        pipeline.settleProjection();
    }

    private static String touchdown(String timestamp) {
        return """
                {"timestamp":"%s","event":"Touchdown",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "PlayerControlled":true,"OnStation":false,"OnPlanet":true}
                """.formatted(timestamp);
    }

    private static String disembark(String timestamp) {
        return """
                {"timestamp":"%s","event":"Disembark",
                 "SRV":true,"ID":10,"StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155,"Body":"Schieni GG-A c3-84 4 a",
                 "BodyID":20,"OnStation":false,"OnPlanet":true}
                """.formatted(timestamp);
    }

    private static String scanOrganic(String timestamp, String scanType) {
        return """
                {"timestamp":"%s","event":"ScanOrganic","ScanType":"%s",
                 "Genus":"$Codex_Ent_Bacterial_Genus_Name;",
                 "Genus_Localised":"Bacteria",
                 "Species":"$Codex_Ent_Bacterial_01_Name;",
                 "Species_Localised":"Bacterium Bullaris",
                 "Variant":"$Codex_Ent_Bacterial_01_F_Name;",
                 "Variant_Localised":"Bacterium Bullaris - Red",
                 "SystemAddress":23155,"Body":20}
                """.formatted(timestamp, scanType);
    }

    // -------------------------------------------------------------- reading

    /**
     * One request per trigger, in bus order, exactly as consecutive turns.
     *
     * <p>Each trigger drains the effects up to itself before the next is built,
     * so no turn sees a change an earlier turn already reported.</p>
     */
    private List<JsonNode> requestsPerTrigger(
            DecisionProductionPipeline pipeline
    ) {
        List<JsonNode> requests = new ArrayList<>();
        for (ProjectedObservation trigger : pipeline.capturedTriggers()) {
            requests.add(read(serializer.serialize(factory.create(
                    pipeline.inputsFor(List.of(trigger))
            ))));
        }
        return List.copyOf(requests);
    }

    /**
     * Every event of one kind, found by the sentences that kind is told with.
     *
     * <p>All of them, not the first: one kind can be several classes, and a
     * sampling sequence is four sentences under {@code BIOLOGICAL_SAMPLE}.
     * Matching a single description would silently return one step of four and
     * leave the assertions below testing a sequence that was never there.</p>
     */
    private List<JsonNode> eventsOfKind(
            DecisionProductionPipeline pipeline,
            String kind
    ) {
        java.util.Set<String> described = pipeline.kindByDescription()
                .entrySet().stream()
                .filter(entry -> entry.getValue().equals(kind))
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        if (described.isEmpty()) {
            throw new AssertionError("no observed event is a " + kind);
        }
        return requestsPerTrigger(pipeline).stream()
                .map(request -> request.path("events").get(0))
                .filter(event ->
                        described.contains(event.path("event").textValue()))
                .toList();
    }

    private List<JsonNode> samplingEvents(DecisionProductionPipeline pipeline) {
        return eventsOfKind(pipeline, "BIOLOGICAL_SAMPLE");
    }

    /** The projected scans themselves, for the counts the graph still keeps. */
    private static List<ProjectedObservation> scans(
            DecisionProductionPipeline pipeline
    ) {
        return pipeline.capturedTriggers().stream()
                .filter(trigger -> trigger.trigger().payload()
                        instanceof ScanOrganic)
                .toList();
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.textValue()));
        return List.copyOf(values);
    }

    private static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
