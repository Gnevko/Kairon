package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static kairon.observer.decision.RequestJson.propertyNames;
import static kairon.observer.decision.RequestJson.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code context.body} carries, whichever event asked for it.
 *
 * <p>A body's ice, its signal counts and the fact that nobody has landed on it
 * were all true before the ship arrived and are still true after. They are the
 * situation, not an event, so they arrive as {@code context.body} — and since
 * they belong to the current-system registry rather than to canonical state
 * (ADR-0025), there is no delta for anything to have to explain away. The body
 * did not become a planet when the ship dropped out of supercruise; it always
 * was one.</p>
 *
 * <p><strong>Merged on 2026-08-08 from {@code RecalledBodyFactsTest} and
 * {@code EstablishedBodyTypeTest}.</strong> The two differed only in which
 * event opened the turn — an approach against a supercruise exit — and asked
 * the same four questions of the same fixture, which each had written out
 * twice. Where they made the same claim it is now made once and asserted for
 * both events, which is stronger than either was: neither had checked that the
 * other's event agreed.</p>
 */
final class BodyContextTest {

    /** Every name the body group may never carry, whatever established it. */
    private static final List<String> NEVER_ON_THE_BODY = List.of(
            "previouslyDiscovered",
            "previouslyMapped",
            "previouslyFootfalled",
            "landable",
            "biologicalSignals",
            "geologicalSignals",
            "distanceFromArrivalLs"
    );

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    @Test
    void anApproachReadsLikeTheMeasuredReplayTurn() {
        JsonNode request = read(serializer.serialize(surveyedThen(APPROACH)));

        assertEquals(
                List.of("events", "context"),
                propertyNames(request),
                "an empty changes section is not serialized"
        );
        JsonNode event = request.path("events").get(0);
        assertEquals(
                "The Commander's ship, in supercruise, came within a body's "
                        + "orbital-cruise zone.",
                event.path("event").textValue()
        );
        assertEquals(
                List.of("event"),
                propertyNames(event),
                "an approach says what happened; where it happened is the "
                        + "situation's to answer"
        );
        assertEquals(
                "Icy One",
                request.path("context").path("body").path("name").textValue()
        );
        assertTrue(
                request.path("context").path("system").path("name")
                        .isMissingNode(),
                "an approach happens inside a system already entered"
        );
        assertFalse(
                request.path("context").has("navigation"),
                "the approach says the ship is in supercruise in its own words"
        );
    }

    @Test
    void aSupercruiseExitReadsLikeTheMeasuredReplayTurn() {
        JsonNode request = read(serializer.serialize(surveyedThen(EXIT)));

        assertEquals(
                List.of("events", "context"),
                propertyNames(request),
                "an empty changes section is not serialized"
        );
        JsonNode event = request.path("events").get(0);
        assertEquals(
                "The Commander's ship left supercruise for normal space.",
                event.path("event").textValue()
        );
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

    /** The coarse type and the specific class both survive, on either event. */
    @Test
    void theBodyCarriesWhatItIs() {
        for (String trigger : List.of(APPROACH, EXIT)) {
            JsonNode body = bodyOf(surveyedThen(trigger));

            assertEquals("Icy One", body.path("name").textValue(), trigger);
            assertEquals(
                    "PLANET",
                    body.path("type").textValue(),
                    "a closed vocabulary is sent in the contract's own casing"
            );
            assertEquals(
                    "Icy body",
                    body.path("planetClass").textValue(),
                    trigger
            );
        }
    }

    /**
     * The body carries what it is, and no record of what was done to it.
     *
     * <p>The scan and the signal reading in this fixture both happened, and both
     * had their own turn. By the time the ship arrives at the body, what they
     * established is neither news nor part of what the body <em>is</em> — so the
     * group carries which body it is and what kind of thing it is, and nothing
     * else. No gravity either: this fixture's scan never measured it.</p>
     */
    @Test
    void nothingASurveyEstablishedRidesOnTheBody() {
        for (String trigger : List.of(APPROACH, EXIT)) {
            JsonNode body = bodyOf(surveyedThen(trigger));

            assertEquals(
                    List.of("name", "type", "planetClass"),
                    propertyNames(body),
                    trigger
            );
            for (String absent : NEVER_ON_THE_BODY) {
                assertFalse(body.has(absent), trigger + ": " + absent);
            }
        }
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
                bodyOf(surveyedThen(APPROACH)).has("gravity"),
                "nothing measured it, and absence is unknown"
        );
        assertFalse(
                serializer.serialize(measuredThenApproached(2.0, true))
                        .contains("2.0"),
                "the measurement itself never travels"
        );
    }

    /**
     * Weight is an arrival's question, and the arrival's alone.
     *
     * <p>It decides whether the descent can be made without wrecking the ship,
     * and it is decided by the time the gear is down. A landing carrying it
     * again is a warning about a descent already survived — in the live session
     * of 2026-08-07 the same "gravity is low" arrived on three landings of one
     * body, after arriving on the approach to it. What the body <em>is</em>
     * still travels with the landing; only the pull stops.</p>
     */
    @Test
    void onlyAnArrivalIsToldHowHeavilyTheBodyPulls() {
        assertEquals(
                "LOW",
                bodyWithGravity(2.0, true).path("gravity").textValue(),
                "the approach asks"
        );
        JsonNode landed = landingOn(2.0);
        assertFalse(
                landed.has("gravity"),
                "and the landing does not: " + landed
        );
        assertEquals(
                "Icy body",
                landed.path("planetClass").textValue(),
                "what the body is still travels with it"
        );
    }

    /** Arriving at a known body reports no change at all, on either event. */
    @Test
    void noChangeVocabularyReachesTheModel() {
        for (String trigger : List.of(APPROACH, EXIT)) {
            String serialized = serializer.serialize(surveyedThen(trigger));

            for (String absent : List.of(
                    "\"changes\"", "\"after\"", "eventId", "\"subject\"",
                    "ESTABLISHED"
            )) {
                assertFalse(serialized.contains(absent), trigger + ": " + absent);
            }
        }
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

    private static final String APPROACH = """
            {"timestamp":"2026-07-30T10:00:03Z",
             "event":"ApproachBody","StarSystem":"Icy System",
             "SystemAddress":23155,"Body":"Icy One","BodyID":20}
            """;

    private static final String EXIT = """
            {"timestamp":"2026-07-30T10:00:03Z",
             "event":"SupercruiseExit","StarSystem":"Icy System",
             "SystemAddress":23155,"Body":"Icy One","BodyID":20,
             "BodyType":"Planet"}
            """;

    private static final String ENTERED_SUPERCRUISE = """
            {"timestamp":"2026-07-30T10:00:00Z","event":"SupercruiseEntry",
             "StarSystem":"Icy System","SystemAddress":23155}
            """;

    /**
     * A body surveyed earlier, arrived at now.
     *
     * <p>The survey is drained into an earlier turn, so by the time the arrival
     * happens the facts are stored rather than newly learned — which is exactly
     * when the projector recalls them.</p>
     */
    private LlmDecisionRequest surveyedThen(String trigger) {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.inputs(List.of(fixture.graphDisabled(ENTERED_SUPERCRUISE)));
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
                fixture.graphDisabled(trigger)
        )));
    }

    /** The same approach, with a body that was measured and may be landed on. */
    private LlmDecisionRequest measuredThenApproached(
            double surfaceGravity,
            boolean landable
    ) {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.inputs(List.of(fixture.graphDisabled(ENTERED_SUPERCRUISE)));
        fixture.graphDisabled(measuredScan(surfaceGravity, landable));
        return factory.create(fixture.inputs(List.of(
                fixture.graphDisabled(APPROACH)
        )));
    }

    /** The same body, landed on rather than approached. */
    private JsonNode landingOn(double surfaceGravity) {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.inputs(List.of(fixture.graphDisabled(ENTERED_SUPERCRUISE)));
        fixture.graphDisabled(measuredScan(surfaceGravity, true));
        fixture.inputs(List.of(fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:02Z",
                 "event":"ApproachBody","StarSystem":"Icy System",
                 "SystemAddress":23155,"Body":"Icy One","BodyID":20}
                """)));
        return bodyOf(factory.create(fixture.inputs(List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:03Z",
                         "event":"Touchdown","PlayerControlled":true,
                         "Body":"Icy One","BodyID":20}
                        """)
        ))));
    }

    private static String measuredScan(
            double surfaceGravity,
            boolean landable
    ) {
        return """
                {"timestamp":"2026-07-30T10:00:01Z","event":"Scan",
                 "SystemAddress":23155,"BodyID":20,"BodyName":"Icy One",
                 "PlanetClass":"Icy body","Landable":%s,
                 "SurfaceGravity":%s,
                 "WasDiscovered":false,"WasMapped":false,
                 "WasFootfalled":false}
                """.formatted(landable, surfaceGravity);
    }

    private JsonNode bodyWithGravity(double surfaceGravity, boolean landable) {
        return bodyOf(measuredThenApproached(surfaceGravity, landable));
    }

    private String gravityOf(double surfaceGravity, boolean landable) {
        return bodyWithGravity(surfaceGravity, landable)
                .path("gravity")
                .textValue();
    }

    private JsonNode bodyOf(LlmDecisionRequest request) {
        return read(serializer.serialize(request))
                .path("context")
                .path("body");
    }
}
