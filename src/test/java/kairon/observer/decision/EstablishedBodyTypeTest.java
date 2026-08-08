package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coarse body type, and why learning it is not an event.
 *
 * <p>The body did not become a planet when the ship dropped out of supercruise;
 * it always was one. What a body is is recorded in the current-system registry
 * and reaches the model as {@code context.body.type} (ADR-0025), so learning it
 * cannot appear in a list of what just changed — there is no canonical field
 * for it to be a delta of.</p>
 */
final class EstablishedBodyTypeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    @Test
    void aSupercruiseExitReadsLikeTheMeasuredReplayTurn() {
        JsonNode request = supercruiseExit();

        assertEquals(
                List.of("events", "context"),
                propertyNames(request),
                "an empty changes section is not serialized"
        );
        JsonNode event = request.path("events").get(0);
        assertEquals(
                    "The Commander's ship left supercruise for normal space.",
                    event.path("event").textValue());
        assertEquals(
                List.of("event"),
                propertyNames(event),
                "dropping out of supercruise leaves the place to the situation"
        );
        assertEquals(
                "Icy One",
                request.path("context").path("body").path("name").textValue()
        );
        assertTrue(
                request.path("context").path("system").path("name")
                        .isMissingNode(),
                "leaving supercruise inside a system is not arriving in one"
        );
        assertFalse(
                request.path("context").has("navigation"),
                "the exit says it dropped into normal space in its own words"
        );
    }

    @Test
    void theInitialBodyTypeArrivesInTheContext() {
        JsonNode body = supercruiseExit().path("context").path("body");

        assertEquals(
                "PLANET",
                body.path("type").textValue(),
                "a closed vocabulary is sent in the contract's own casing"
        );
        assertEquals(
                "Icy body",
                body.path("planetClass").textValue(),
                "the coarse type and the specific class both survive"
        );
    }

    @Test
    void noEstablishedTypeChangeReachesTheModel() {
        String serialized = serializer.serialize(supercruiseExitRequest());

        assertFalse(serialized.contains("ESTABLISHED"));
        assertFalse(serialized.contains("\"after\""));
        assertFalse(serialized.contains("\"changes\""));
    }

    /**
     * What a survey found is the survey's to report, not the body's to carry.
     *
     * <p>The scan and the signal reading in this fixture both happened, and
     * both had their own turn. By the time the ship drops out of supercruise
     * onto the body, what they established is neither news nor part of what
     * the body <em>is</em> — so the group carries which body it is and what
     * kind of thing it is, and nothing else.</p>
     */
    @Test
    void whatASurveyFoundDoesNotRideOnTheBody() {
        JsonNode body = supercruiseExit().path("context").path("body");

        assertEquals(
                List.of("name", "type", "planetClass"),
                propertyNames(body)
        );
        for (String absent : List.of(
                "previouslyDiscovered",
                "previouslyMapped",
                "previouslyFootfalled",
                "landable",
                "biologicalSignals",
                "geologicalSignals",
                "distanceFromArrivalLs"
        )) {
            assertFalse(body.has(absent), absent);
        }
    }

    // ------------------------------------------------------------- fixtures

    /** A body surveyed earlier, dropped out of supercruise onto now. */
    private LlmDecisionRequest supercruiseExitRequest() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.inputs(List.of(fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"SupercruiseEntry",
                 "StarSystem":"Icy System","SystemAddress":23155}
                """)));
        fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"Scan",
                 "SystemAddress":23155,"BodyID":20,"BodyName":"Icy One",
                 "PlanetClass":"Icy body","Landable":true,
                 "WasDiscovered":false,"WasMapped":false,
                 "WasFootfalled":false,
                 "DistanceFromArrivalLS":1081.453145}
                """);
        fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:02Z","event":"SAASignalsFound",
                 "SystemAddress":23155,"BodyID":20,"BodyName":"Icy One",
                 "Signals":[{"Type":"$SAA_SignalType_Biological;",
                 "Type_Localised":"Biological","Count":1}]}
                """);
        return factory.create(fixture.inputs(List.of(
                fixture.graphDisabled(exit("Planet", 3))
        )));
    }

    private JsonNode supercruiseExit() {
        return read(serializer.serialize(supercruiseExitRequest()));
    }

    private static String exit(String bodyType, int index) {
        return """
                {"timestamp":"2026-07-30T10:00:%02dZ",
                 "event":"SupercruiseExit","StarSystem":"Icy System",
                 "SystemAddress":23155,"Body":"Icy One","BodyID":20,
                 "BodyType":"%s"}
                """.formatted(index, bodyType);
    }

    private static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static List<String> propertyNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }
}
