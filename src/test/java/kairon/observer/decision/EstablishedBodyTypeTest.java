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
                    "A ship dropped out of supercruise into normal space.",
                    event.path("event").textValue());
        assertEquals("Icy One", event.path("body").textValue());
        assertEquals("Icy System", event.path("system").textValue());
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
     * Absence means unknown, so an established {@code false} must still be sent
     * — and an unmeasured count must not be.
     */
    @Test
    void anEstablishedFalseSurvivesAndAnUnmeasuredCountStaysAbsent() {
        JsonNode body = supercruiseExit().path("context").path("body");

        for (String flag : List.of(
                "previouslyDiscovered",
                "previouslyMapped",
                "previouslyFootfalled"
        )) {
            assertTrue(body.has(flag), flag + " was established as false");
            assertFalse(body.path(flag).booleanValue());
        }
        assertTrue(body.path("landable").booleanValue());
        assertFalse(
                body.has("geologicalSignals"),
                "the reading counted biology and said nothing about geology"
        );
        assertEquals(1, body.path("biologicalSignals").intValue());
        assertFalse(body.has("distanceFromArrivalLs"));
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
