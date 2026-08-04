package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.semantics.SemanticChangeKind;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticProvenance;
import kairon.semantics.SemanticSourceRole;
import kairon.semantics.SemanticStateChange;
import kairon.semantics.SemanticValue;
import kairon.semantics.SemanticValueOrigin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coarse body type, and why learning it is not an event.
 *
 * <p>A body's type is unknown until some event happens to carry it. The first
 * one that does establishes it — but the body did not become a planet when the
 * ship dropped out of supercruise, it always was one. That belongs in the
 * situation, not in a list of what just changed.</p>
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
        assertEquals(
                "NORMAL_SPACE",
                request.path("context").path("navigation")
                        .path("flightMode").textValue(),
                "navigation context is untouched"
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
        assertEquals(
                1081.453145,
                body.path("distanceFromArrivalLs").doubleValue(),
                0.000001
        );
    }

    @Test
    void onlyTheFirstEstablishmentOfThisOneFieldIsTouched() {
        assertTrue(DecisionChangeSelector.establishedBodyType(
                bodyType(SemanticChangeKind.ESTABLISHED)
        ));
        for (SemanticChangeKind real : List.of(
                SemanticChangeKind.UPDATED,
                SemanticChangeKind.CLEARED
        )) {
            assertFalse(
                    DecisionChangeSelector.establishedBodyType(bodyType(real)),
                    real + " is a real change and this rule must ignore it"
            );
        }
        assertFalse(
                DecisionChangeSelector.establishedBodyType(
                        new SemanticStateChange(
                                SemanticField.PLANET_CLASS,
                                SemanticValue.unknown(),
                                SemanticValue.ofText("Icy body"),
                                SemanticChangeKind.ESTABLISHED,
                                SemanticValueOrigin.OBSERVATION,
                                provenance()
                        )
                ),
                "no other field is affected"
        );
    }

    /**
     * A body type that later changes is a real update.
     *
     * <p>The first exit establishes it and is silent; a second exit reporting a
     * different type is something that happened, and it reaches the model.</p>
     */
    @Test
    void aLaterChangeOfBodyTypeStillReachesTheModel() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.inputs(List.of(fixture.graphDisabled(exit("Planet", 0))));
        JsonNode request = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphDisabled(exit("Star", 1))))
        ).request()));

        JsonNode body = null;
        for (JsonNode change : request.path("changes")) {
            if ("body".equals(change.path("subject").textValue())) {
                body = change;
            }
        }
        assertTrue(body != null, "the type actually changed");
        assertEquals("UPDATED", body.path("kind").textValue());
        assertEquals(
                "Planet",
                body.path("fields").path("type").path("before").textValue()
        );
        assertEquals(
                "Star",
                body.path("fields").path("type").path("after").textValue()
        );
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
        ))).request();
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

    private static SemanticStateChange bodyType(SemanticChangeKind kind) {
        return new SemanticStateChange(
                SemanticField.BROAD_BODY_TYPE,
                kind == SemanticChangeKind.ESTABLISHED
                        ? SemanticValue.unknown()
                        : SemanticValue.ofSymbol("Planet"),
                kind == SemanticChangeKind.CLEARED
                        ? SemanticValue.unknown()
                        : SemanticValue.ofSymbol("Star"),
                kind,
                SemanticValueOrigin.OBSERVATION,
                provenance()
        );
    }

    private static SemanticProvenance provenance() {
        return new SemanticProvenance(
                1L,
                SemanticSourceRole.NEW,
                "SupercruiseExit",
                "observation-1"
        );
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
