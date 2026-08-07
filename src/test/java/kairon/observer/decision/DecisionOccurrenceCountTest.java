package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How often this has happened here, against the real graph.
 *
 * <p>Every case runs the production parser, projector and behavior graph. A
 * scripted trajectory could assert the arithmetic but not the thing that
 * actually matters — that the graph records a body with an occurrence at all,
 * and records the right one.</p>
 */
final class DecisionOccurrenceCountTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /** The first landing is the first, and says so rather than staying silent. */
    @Test
    void aFirstLandingIsCountedAsTheFirst(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrive(pipeline);
            pipeline.journal(touchdown("2026-07-30T10:01:00Z"));
            pipeline.settleProjection();

            JsonNode event = lastEvent(pipeline);
            assertEquals(
                    "A ship landed on the surface of a planet or moon.",
                    event.path("event").textValue());
            assertEquals(1, event.path("occurrenceOnBody").intValue());
        }
    }

    /**
     * Landing on the same body again is the second time.
     *
     * <p>The lift-off between them is what makes the two landings two events
     * rather than one, and it is also what a comment about "again" has to be
     * true of.</p>
     */
    @Test
    void aSecondLandingOnTheSameBodyIsCountedAsTheSecond(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrive(pipeline);
            pipeline.journal(touchdown("2026-07-30T10:01:00Z"));
            pipeline.journal(liftoff("2026-07-30T10:02:00Z"));
            pipeline.journal(touchdown("2026-07-30T10:03:00Z"));
            pipeline.settleProjection();

            JsonNode event = lastEvent(pipeline);
            assertEquals(
                    "A ship landed on the surface of a planet or moon.",
                    event.path("event").textValue());
            assertEquals(2, event.path("occurrenceOnBody").intValue());
        }
    }

    /**
     * A landing on a different body is not a repeat of the first.
     *
     * <p>Both landings are in one system visit, so a count scoped to the visit
     * would say two. The count is scoped to the body, so it says one.</p>
     */
    @Test
    void aLandingOnAnotherBodyStartsItsOwnCount(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrive(pipeline);
            pipeline.journal(touchdown("2026-07-30T10:01:00Z"));
            pipeline.journal(liftoff("2026-07-30T10:02:00Z"));
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:03:00Z","event":"Touchdown",
                     "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                     "Body":"Schieni GG-A c3-84 5 b","BodyID":21,
                     "PlayerControlled":true,"OnStation":false,"OnPlanet":true}
                    """);
            pipeline.settleProjection();

            assertEquals(
                    1,
                    lastEvent(pipeline).path("occurrenceOnBody").intValue()
            );
        }
    }

    /**
     * The same body id in another system is a different body.
     *
     * <p>Body ids are only unique inside their own system, so the fourth body
     * of one system and the fourth body of the next share an id and nothing
     * else. Landing on both must not read as landing twice on one.</p>
     */
    @Test
    void theSameBodyIdInAnotherSystemIsNotTheSameBody(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrive(pipeline);
            pipeline.journal(touchdown("2026-07-30T10:01:00Z"));
            pipeline.journal(liftoff("2026-07-30T10:02:00Z"));
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:04:00Z","event":"FSDJump",
                     "StarSystem":"Colonia","SystemAddress":99999,
                     "JumpDist":12.5}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:05:00Z","event":"Touchdown",
                     "StarSystem":"Colonia","SystemAddress":99999,
                     "Body":"Colonia 4 a","BodyID":20,
                     "PlayerControlled":true,"OnStation":false,"OnPlanet":true}
                    """);
            pipeline.settleProjection();

            JsonNode event = lastEvent(pipeline);
            assertEquals(
                    "A ship landed on the surface of a planet or moon.",
                    event.path("event").textValue());
            assertEquals(
                    1,
                    event.path("occurrenceOnBody").intValue(),
                    "body id 20 in Colonia is not body id 20 in Schieni"
            );
        }
    }

    /**
     * An event the graph has placed nowhere carries no count.
     *
     * <p>A friend coming online happens to the Commander, not at a body. There
     * is no scope for a count to be true of, and inventing one — over the
     * system visit, or over all time — would be a number the model could only
     * misread.</p>
     */
    @Test
    void anEventWithNoBodyCarriesNoCount(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrive(pipeline);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:01:00Z","event":"Friends",
                     "Status":"Online","Name":"KotyaGaw"}
                    """);
            pipeline.settleProjection();

            JsonNode event = lastEvent(pipeline);
            assertEquals(
                    "Information about a friend's status was received.",
                    event.path("event").textValue());
            assertFalse(event.has("occurrenceOnBody"));
        }
    }

    /**
     * The measured second landing, whole.
     *
     * <p>The structure this task specified, asserted end to end: the count on
     * the event, the body context with its meaningful falses and zero intact,
     * and the trajectory that says what the Commander was doing before it.</p>
     */
    @Test
    void theSecondLandingCarriesItsCountItsContextAndItsHistory(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            arrive(pipeline);
            pipeline.journal(touchdown("2026-07-30T10:01:00Z"));
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:01:30Z","event":"ScanOrganic",
                     "ScanType":"Log","SystemAddress":23155,"Body":20,
                     "Genus":"$Codex_Ent_Bacterial_Genus_Name;",
                     "Genus_Localised":"Bacteria",
                     "Variant":"$Codex_Ent_Bacterial_01_F_Name;",
                     "Variant_Localised":"Bacterium Bullaris - Red"}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:01:40Z","event":"Embark",
                     "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                     "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                     "OnStation":false,"OnPlanet":true,"SRV":true,"ID":10}
                    """);
            pipeline.journal(liftoff("2026-07-30T10:02:00Z"));
            pipeline.journal(touchdown("2026-07-30T10:03:00Z"));
            pipeline.settleProjection();

            JsonNode request = lastRequest(pipeline);
            JsonNode event = request.path("events").get(0);
            assertEquals(
                    List.of("event", "commanderControlled", "occurrenceOnBody"),
                    propertyNames(event),
                    "a landing does not name the body it landed on; the "
                            + "situation answers for where the ship is"
            );
            assertEquals(
                    "A ship landed on the surface of a planet or moon.",
                    event.path("event").textValue());
            assertTrue(event.path("commanderControlled").booleanValue());
            assertEquals(2, event.path("occurrenceOnBody").intValue());

            JsonNode body = request.path("context").path("body");
            assertEquals(
                    List.of("name", "type", "planetClass"),
                    propertyNames(body),
                    "what the body is, and nothing that was merely found on it"
            );
            assertEquals(
                    "Schieni GG-A c3-84 4 a",
                    body.path("name").textValue(),
                    "the name the event stopped carrying arrives here instead"
            );
            assertFalse(
                    body.has("biologicalSignals"),
                    "what a survey found is the survey's to report"
            );
            assertFalse(body.has("previouslyDiscovered"));
            assertFalse(body.has("landable"));
            assertFalse(body.has("distanceFromArrivalLs"));
            assertEquals(
                    "Schieni GG-A c3-84",
                    request.path("context").path("system").path("name")
                            .textValue()
            );
            assertFalse(
                    request.path("context").has("navigation"),
                    "the landing says the ship is down in its own words"
            );

        }
    }

    // ------------------------------------------------------------- fixtures

    /** A surveyed body in a known system, with the graph already owning it. */
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

    private static String touchdown(String timestamp) {
        return """
                {"timestamp":"%s","event":"Touchdown",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "PlayerControlled":true,"OnStation":false,"OnPlanet":true}
                """.formatted(timestamp);
    }

    private static String liftoff(String timestamp) {
        return """
                {"timestamp":"%s","event":"Liftoff",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "PlayerControlled":true,"OnStation":false,"OnPlanet":true}
                """.formatted(timestamp);
    }

    /**
     * The request one turn would build from the last observation alone.
     *
     * <p>Everything before it is drained through an earlier turn first, exactly
     * as a replay would: by the time the landing happens, arriving in the system
     * and surveying the body are standing background rather than news.</p>
     */
    private JsonNode lastRequest(DecisionProductionPipeline pipeline) {
        List<ProjectedObservation> triggers = pipeline.capturedTriggers();
        if (triggers.size() > 1) {
            pipeline.inputsFor(List.of(triggers.get(triggers.size() - 2)));
        }
        return read(serializer.serialize(factory.create(
                pipeline.inputsFor(List.of(triggers.getLast()))
        )));
    }

    private JsonNode lastEvent(DecisionProductionPipeline pipeline) {
        return lastRequest(pipeline).path("events").get(0);
    }

    private static List<String> propertyNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }

    private static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
