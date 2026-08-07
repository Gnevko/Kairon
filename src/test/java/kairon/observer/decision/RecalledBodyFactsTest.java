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
 * What is known about a body, and where the model reads it.
 *
 * <p>A body's ice, its signal counts and the fact that nobody has landed on it
 * were all true before the approach and are still true after it. They are the
 * situation, not an event, so they arrive as {@code context.body} — and since
 * they are the current-system registry's rather than canonical state's
 * (ADR-0025), there is no delta for anything to have to explain away.</p>
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
        assertEquals(
                    "A ship in supercruise came within a body's orbital-cruise zone.",
                    event.path("event").textValue());
        assertEquals("Icy One", event.path("body").textValue());
        assertEquals("Icy System", event.path("system").textValue());
        assertFalse(
                request.path("context").has("navigation"),
                "the approach says the ship is in supercruise in its own words"
        );
    }

    @Test
    void recalledBodyFactsArriveInTheContext() {
        JsonNode body = approach().path("context").path("body");

        assertEquals("Icy body", body.path("planetClass").textValue());
        assertTrue(body.path("landable").booleanValue());
        assertFalse(body.has("distanceFromArrivalLs"));
        assertEquals(1, body.path("biologicalSignals").intValue());
    }

    /**
     * How heavily the body pulls, in the three words the contract has for it.
     *
     * <p>Both halves of the claim are asserted: the band each side of both
     * thresholds, and that the number itself never travels. A measurement in
     * metres per second squared is not what a remark rests on, and the
     * thresholds — half a g and one and a half — are stated in
     * {@code DecisionNames.gravityBand} rather than here, so this reads them
     * rather than restating them.</p>
     */
    @Test
    void aLandableBodyIsBandedByHowHeavilyItPulls() {
        assertEquals("LOW", gravityOf(2.0, true));
        assertEquals("LOW", gravityOf(4.8, true));
        assertEquals("NORMAL", gravityOf(4.95, true));
        assertEquals("NORMAL", gravityOf(9.80665, true));
        assertEquals("NORMAL", gravityOf(14.7, true));
        assertEquals("HIGH", gravityOf(14.85, true));
        assertEquals("HIGH", gravityOf(30.0, true));

        assertFalse(
                bodyWithGravity(20.0, false).has("gravity"),
                "nothing puts down on it, so how heavy it is says nothing"
        );
        assertFalse(
                approach().path("context").path("body").has("gravity"),
                "nothing measured it, and absence is unknown"
        );
        assertFalse(
                serializer.serialize(approachRequest(2.0, true))
                        .contains("2.0"),
                "the measurement itself never travels"
        );
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

    /** An approach to a known body reports no change at all. */
    @Test
    void noChangeVocabularyReachesTheModel() {
        String serialized = serializer.serialize(approachRequest());

        assertFalse(serialized.contains("\"changes\""));
        assertFalse(serialized.contains("\"after\""));
        assertFalse(serialized.contains("eventId"));
        assertFalse(serialized.contains("\"subject\""));
    }

    /**
     * A real change still reaches the model.
     *
     * <p>Recovering an SRV moves the Commander back aboard the ship. That is
     * something that happened, not something recalled, and it survives.</p>
     */
    @Test
    void aRealChangeStillReachesTheModel() {
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
        )));

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
        )));
    }

    private JsonNode approach() {
        return read(serializer.serialize(approachRequest()));
    }

    /** The same approach, with a body that was measured and may be landed on. */
    private LlmDecisionRequest approachRequest(
            double surfaceGravity,
            boolean landable
    ) {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.inputs(List.of(fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"SupercruiseEntry",
                 "StarSystem":"Icy System","SystemAddress":23155}
                """)));
        fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"Scan",
                 "SystemAddress":23155,"BodyID":20,"BodyName":"Icy One",
                 "PlanetClass":"Icy body","Landable":%s,
                 "SurfaceGravity":%s,
                 "WasDiscovered":false,"WasMapped":false,
                 "WasFootfalled":false}
                """.formatted(landable, surfaceGravity));
        return factory.create(fixture.inputs(List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:03Z",
                         "event":"ApproachBody","StarSystem":"Icy System",
                         "SystemAddress":23155,"Body":"Icy One","BodyID":20}
                        """)
        )));
    }

    private JsonNode bodyWithGravity(double surfaceGravity, boolean landable) {
        return read(serializer.serialize(
                approachRequest(surfaceGravity, landable)
        )).path("context").path("body");
    }

    private String gravityOf(double surfaceGravity, boolean landable) {
        return bodyWithGravity(surfaceGravity, landable)
                .path("gravity")
                .textValue();
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
