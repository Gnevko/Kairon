package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A detailed body scan is a result the Commander went and got.
 *
 * <p>It used to be background. A measured replay of three hundred journal
 * records carried forty-nine {@code Scan} records through the pipeline and
 * produced no occurrence and no turn from any of them: a whole system's worth
 * of exploration reached the model as nothing at all.</p>
 *
 * <p>Only the detailed depth. An automatic scan is the ship noticing a body it
 * flew past, and a basic one is a name and a distance — neither is a result,
 * and neither opens a turn. And only once per body per visit: the record
 * establishes the body, and repeating it establishes nothing further.</p>
 *
 * <p>Everything here runs the production parser, projector and behaviour graph
 * against isolated temporary storage. The provider is a stub that cannot
 * influence what is built.</p>
 */
final class BodyScanEventTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /** C1: a barycentre is orbital arithmetic, and stays out entirely. */
    @Test
    void aBarycentreScanIsNotABodyScan(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:01:00Z",
                     "event":"ScanBaryCentre","StarSystem":"Schieni",
                     "SystemAddress":23155,"BodyID":8,"SemiMajorAxis":1.0}
                    """);
            pipeline.settleProjection();

            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.BODY_SCANNED
                    )
            );
            assertEquals(
                    List.of(NormalizedEventType.SYSTEM_ENTRY),
                    pipeline.episodeTypes()
            );
            assertTrue(
                    pipeline.capturedTriggers().stream().noneMatch(trigger ->
                            trigger.trigger().payload().getClass()
                                    .getSimpleName()
                                    .equals("ScanBaryCentre"))
            );
        }
    }

    /** C2: a detailed planet scan, with what a comment could use and no more. */
    @Test
    void aDetailedPlanetScanBecomesOneModelFacingEvent(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(detailedPlanet(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "Icy body",
                    1081.453145
            ));
            pipeline.settleProjection();

            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.BODY_SCANNED
                    )
            );
            assertEquals(
                    """
                    {"event":"A discovery scan reported a star, \
                    planet or moon's properties.","body":"Schieni 4 a",\
                    "system":"Schieni","scanType":"DETAILED",\
                    "bodyType":"PLANET","planetClass":"Icy body",\
                    "landable":true,"terraformState":"Terraformable",\
                    "atmosphere":"thin sulphur dioxide atmosphere",\
                    "volcanism":"major water geysers volcanism",\
                    "previouslyDiscovered":false,"previouslyMapped":false,\
                    "previouslyFootfalled":false,\
                    "distanceFromArrivalLs":1081.453145}""",
                    eventJson(pipeline, "Scan")
            );

            String serialized = requestFor(pipeline, "Scan").toString();
            for (String internal : List.of(
                    "SystemAddress",
                    "BodyID",
                    "23155",
                    "occurrenceOnBody",
                    "Materials",
                    "Composition",
                    "SurfaceGravity",
                    "Radius",
                    "MassEM"
            )) {
                assertFalse(
                        serialized.contains(internal),
                        internal + " reached the provider: " + serialized
                );
            }
        }
    }

    /** C3: a star says what kind of star, and nothing about planet classes. */
    @Test
    void aDetailedStarScanCarriesTheStellarClassification(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:01:00Z","event":"Scan",
                     "ScanType":"Detailed","StarSystem":"Schieni",
                     "SystemAddress":23155,"BodyID":0,"BodyName":"Schieni A",
                     "StarType":"K","Subclass":3,"StellarMass":0.7,
                     "WasDiscovered":true,"WasMapped":false,
                     "DistanceFromArrivalLS":0.0}
                    """);
            pipeline.settleProjection();

            JsonNode event = requestFor(pipeline, "Scan")
                    .path("events").get(0);
            assertEquals("STAR", event.path("bodyType").textValue());
            assertEquals("K", event.path("starType").textValue());
            assertFalse(event.has("planetClass"));
            assertFalse(event.has("landable"));
        }
    }

    /** C4: the ship noticing a body in passing is not a result. */
    @Test
    void anAutomaticScanEstablishesTheBodyAndOpensNoTurn(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:01:00Z","event":"ApproachBody",
                     "StarSystem":"Schieni","SystemAddress":23155,
                     "Body":"Schieni 4 a","BodyID":20}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:01:01Z","event":"Scan",
                     "ScanType":"AutoScan","StarSystem":"Schieni",
                     "SystemAddress":23155,"BodyID":20,
                     "BodyName":"Schieni 4 a","PlanetClass":"Icy body",
                     "Landable":true,"WasDiscovered":false,"WasMapped":false}
                    """);
            pipeline.settleProjection();
            String established = pipeline.capturedProjections().getLast()
                    .currentState().planetClass();
            turn(pipeline, "2026-07-30T10:01:02Z");

            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.BODY_SCANNED
                    )
            );
            assertEquals(
                    List.of("SYSTEM_JUMP", "BODY_APPROACHED"),
                    pipeline.modelFacingKinds()
            );
            assertEquals(
                    "Icy body",
                    established,
                    "the body is still established, it just is not news"
            );
        }
    }

    /** C5: and neither is a depth nobody has researched. */
    @Test
    void anUnknownOrMissingScanDepthIsNotDetailed(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:01:00Z","event":"Scan",
                     "ScanType":"NavBeaconDetail","StarSystem":"Schieni",
                     "SystemAddress":23155,"BodyID":20,
                     "BodyName":"Schieni 4 a","PlanetClass":"Icy body"}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:01:01Z","event":"Scan",
                     "StarSystem":"Schieni","SystemAddress":23155,
                     "BodyID":21,"BodyName":"Schieni 4 b",
                     "PlanetClass":"Rocky body"}
                    """);
            turn(pipeline, "2026-07-30T10:01:02Z");

            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.BODY_SCANNED
                    )
            );
            assertEquals(
                    List.of("SYSTEM_JUMP"),
                    pipeline.modelFacingKinds(),
                    "neither depth established anything to report"
            );
        }
    }

    /** C6: saying the same thing twice is one result. */
    @Test
    void anIdenticalRepeatOfTheSameScanIsNotASecondResult(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(detailedPlanet(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "Icy body",
                    1081.453145
            ));
            pipeline.journal(detailedPlanet(
                    "2026-07-30T10:01:05Z",
                    20,
                    "Schieni 4 a",
                    "Icy body",
                    1081.453145
            ));
            turn(pipeline, "2026-07-30T10:01:06Z");

            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.BODY_SCANNED
                    )
            );
            assertEquals(
                    List.of("SYSTEM_JUMP", "BODY_SCANNED"),
                    pipeline.modelFacingKinds()
            );
            assertEquals(
                    1L,
                    pipeline.edge(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.BODY_SCANNED
                    ).globalCounter().rawCount()
            );
        }
    }

    /** C7: saying something different is another. */
    @Test
    void aChangedReadingOfTheSameBodyIsANewResult(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(detailedPlanet(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "Icy body",
                    1081.453145
            ));
            pipeline.journal(detailedPlanet(
                    "2026-07-30T10:01:05Z",
                    20,
                    "Schieni 4 a",
                    "Rocky ice body",
                    1081.453145
            ));
            pipeline.settleProjection();
            List<NormalizedEventType> run = pipeline.episodeTypes();
            turn(pipeline, "2026-07-30T10:01:06Z");

            assertEquals(
                    2L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.BODY_SCANNED
                    )
            );
            assertEquals(
                    List.of("SYSTEM_JUMP", "BODY_SCANNED", "BODY_SCANNED"),
                    pipeline.modelFacingKinds()
            );
            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.BODY_SCANNED,
                            NormalizedEventType.BODY_SCANNED
                    ),
                    run
            );
        }
    }

    /** C8: and two bodies are never each other. */
    @Test
    void twoBodiesAreNeverDeduplicatedAgainstEachOther(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrived(pipeline);
            pipeline.journal(detailedPlanet(
                    "2026-07-30T10:01:00Z",
                    20,
                    "Schieni 4 a",
                    "Icy body",
                    1081.453145
            ));
            pipeline.journal(detailedPlanet(
                    "2026-07-30T10:01:01Z",
                    21,
                    "Schieni 4 b",
                    "Icy body",
                    1081.453145
            ));
            pipeline.settleProjection();

            assertEquals(
                    2L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.BODY_SCANNED
                    )
            );
            assertEquals(
                    List.of("Schieni 4 a", "Schieni 4 b"),
                    triggersOf(pipeline, "Scan").stream()
                            .map(trigger -> requestOf(pipeline, trigger)
                                    .path("events").get(0)
                                    .path("body").textValue())
                            .toList()
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    /** Closes the open batch and lets the turn run, as replay does. */
    private static void turn(
            DecisionProductionPipeline pipeline,
            String timestamp
    ) throws Exception {
        pipeline.replayExhausted(timestamp);
        pipeline.settle();
    }

    /** A real arrival, so the visit has an entry to hang results off. */
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

    private static String detailedPlanet(
            String timestamp,
            int bodyId,
            String bodyName,
            String planetClass,
            double distance
    ) {
        return "{\"timestamp\":\"" + timestamp + "\",\"event\":\"Scan\","
                + "\"ScanType\":\"Detailed\",\"StarSystem\":\"Schieni\","
                + "\"SystemAddress\":23155,\"BodyID\":" + bodyId + ","
                + "\"BodyName\":\"" + bodyName + "\","
                + "\"PlanetClass\":\"" + planetClass + "\","
                + "\"TerraformState\":\"Terraformable\","
                + "\"Atmosphere\":\"thin sulphur dioxide atmosphere\","
                + "\"Volcanism\":\"major water geysers volcanism\","
                + "\"Landable\":true,\"WasDiscovered\":false,"
                + "\"WasMapped\":false,\"WasFootfalled\":false,"
                + "\"MassEM\":0.0032,\"Radius\":1051000.0,"
                + "\"SurfaceGravity\":1.2,"
                + "\"DistanceFromArrivalLS\":" + distance + ","
                + "\"Materials\":[{\"Name\":\"iron\",\"Percent\":20.1}]}";
    }

    // -------------------------------------------------------------- reading

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
        List<ProjectedObservation> wanted =
                triggersOf(pipeline, payloadSimpleName);
        if (wanted.isEmpty()) {
            throw new AssertionError(
                    payloadSimpleName + " never became a trigger"
            );
        }
        return requestOf(pipeline, wanted.getFirst());
    }

    /** The turn this one trigger would build, with everything before drained. */
    private JsonNode requestOf(
            DecisionProductionPipeline pipeline,
            ProjectedObservation wanted
    ) {
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

    private static List<ProjectedObservation> triggersOf(
            DecisionProductionPipeline pipeline,
            String payloadSimpleName
    ) {
        return pipeline.capturedTriggers().stream()
                .filter(projected -> projected.trigger().payload().getClass()
                        .getSimpleName().equals(payloadSimpleName))
                .toList();
    }

    private static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
