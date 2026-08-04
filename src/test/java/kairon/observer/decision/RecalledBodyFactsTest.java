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
 * What the projector recalls about a body, and where the model reads it.
 *
 * <p>A body's ice, its signal counts and the fact that nobody has landed on it
 * were all true before the approach and are still true after it. They are the
 * situation, not an event, so they arrive as {@code context.body} — and the
 * internal write-path vocabulary that would otherwise be needed to explain that
 * never leaves Kairon.</p>
 */
final class RecalledBodyFactsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    @Test
    void anApproachReadsLikeTheMeasuredReplayTurn() {
        JsonNode request = approach();

        assertEquals(
                List.of("events", "context"),
                propertyNames(request),
                "an empty changes section is not serialized"
        );
        JsonNode event = request.path("events").get(0);
        assertEquals("BODY_APPROACHED", event.path("kind").textValue());
        assertEquals("Icy One", event.path("body").textValue());
        assertEquals("Icy System", event.path("system").textValue());
        assertEquals(
                "SUPERCRUISE",
                request.path("context").path("navigation")
                        .path("flightMode").textValue(),
                "navigation context is untouched"
        );
    }

    @Test
    void recalledBodyFactsArriveInTheContext() {
        JsonNode body = approach().path("context").path("body");

        assertEquals("Icy body", body.path("planetClass").textValue());
        assertTrue(body.path("landable").booleanValue());
        assertEquals(
                1081.453145,
                body.path("distanceFromArrivalLs").doubleValue(),
                0.000001
        );
        assertEquals(1, body.path("biologicalSignals").intValue());
    }

    /**
     * Absence means unknown, so an established {@code false} must still be sent
     * — and a category nobody counted must not be.
     *
     * <p>The two halves are the same rule read in both directions. A survey flag
     * the game reported as {@code false} is a fact and is sent; a signal
     * category the reading never mentioned is not a fact, and a zero in its
     * place would say that somebody counted and found none.</p>
     */
    @Test
    void anEstablishedFalseSurvivesAndAnUnmeasuredCountStaysAbsent() {
        JsonNode body = approach().path("context").path("body");

        for (String flag : List.of(
                "previouslyDiscovered",
                "previouslyMapped",
                "previouslyFootfalled"
        )) {
            assertTrue(body.has(flag), flag + " was established as false");
            assertFalse(body.path(flag).booleanValue());
        }
        assertFalse(
                body.has("geologicalSignals"),
                "no reading counted geology here, so nothing says it is zero"
        );
    }

    @Test
    void noWritePathVocabularyReachesTheModel() {
        String serialized = serializer.serialize(approachRequest());

        assertFalse(serialized.contains("ACTIVATED_FROM_CONTEXT"));
        assertFalse(serialized.contains("STORED_CONTEXT"));
        assertFalse(serialized.contains("\"after\""));
        assertFalse(serialized.contains("eventId"));
        assertFalse(serialized.contains("\"subject\""));
    }

    @Test
    void aRecallIsTheOnlyChangeKindThisRuleTouches() {
        assertTrue(DecisionChangeSelector.recalledFromRegistry(
                change(SemanticChangeKind.ACTIVATED_FROM_CONTEXT)
        ));
        for (SemanticChangeKind real : List.of(
                SemanticChangeKind.ESTABLISHED,
                SemanticChangeKind.UPDATED,
                SemanticChangeKind.CLEARED
        )) {
            assertFalse(
                    DecisionChangeSelector.recalledFromRegistry(change(real)),
                    real + " is a real change and this rule must ignore it"
            );
        }
    }

    /**
     * A real change still reaches the model.
     *
     * <p>Recovering an SRV moves the Commander back aboard the ship. That is
     * something that happened, not something recalled, and it survives.</p>
     */
    @Test
    void aRealChangeIsUnaffectedByTheRecallRule() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.inputs(List.of(fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Embark",
                 "SRV":true,"ID":10,"StarSystem":"Icy System",
                 "Body":"Icy One","OnStation":false,"OnPlanet":true}
                """)));
        JsonNode request = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:01Z","event":"DockSRV",
                         "ID":10,"SRVType_Localised":"Nomad"}
                        """)))
        ).request()));

        JsonNode presence = null;
        for (JsonNode change : request.path("changes")) {
            if ("commander".equals(change.path("subject").textValue())) {
                presence = change;
            }
        }
        assertTrue(presence != null, "moving back aboard is a real change");
        assertEquals("UPDATED", presence.path("kind").textValue());
        assertEquals(
                "SHIP",
                presence.path("fields").path("presence")
                        .path("after").textValue()
        );
    }

    // ------------------------------------------------------------- fixtures

    /**
     * A body surveyed earlier, approached now.
     *
     * <p>The survey is drained into an earlier turn, so by the time the
     * approach happens the facts are stored rather than newly learned — which
     * is exactly when the projector recalls them.</p>
     */
    private LlmDecisionRequest approachRequest() {
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
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:03Z",
                         "event":"ApproachBody","StarSystem":"Icy System",
                         "SystemAddress":23155,"Body":"Icy One","BodyID":20}
                        """)
        ))).request();
    }

    private JsonNode approach() {
        return read(serializer.serialize(approachRequest()));
    }

    private static SemanticStateChange change(SemanticChangeKind kind) {
        SemanticValue before = kind == SemanticChangeKind.ESTABLISHED
                || kind == SemanticChangeKind.ACTIVATED_FROM_CONTEXT
                ? SemanticValue.unknown()
                : SemanticValue.ofText("Rocky body");
        SemanticValue after = kind == SemanticChangeKind.CLEARED
                ? SemanticValue.unknown()
                : SemanticValue.ofText("Icy body");
        return new SemanticStateChange(
                SemanticField.PLANET_CLASS,
                before,
                after,
                kind,
                kind == SemanticChangeKind.ACTIVATED_FROM_CONTEXT
                        ? SemanticValueOrigin.STORED_CONTEXT
                        : SemanticValueOrigin.OBSERVATION,
                new SemanticProvenance(
                        1L,
                        SemanticSourceRole.NEW,
                        "ApproachBody",
                        "observation-1"
                )
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
