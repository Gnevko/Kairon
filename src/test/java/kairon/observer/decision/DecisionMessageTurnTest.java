package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.journal.JournalEventObservation;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A message is what it says, and it says it in the contract's own casing.
 *
 * <p>Two claims, both about the projection alone. The journal's channel name
 * reaches the model as a closed domain value like every other closed vocabulary
 * in the contract; and a turn that is nothing but somebody talking carries no
 * flight history, because where the ship has been cannot make a greeting easier
 * to read.</p>
 */
final class DecisionMessageTurnTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /** The measured squadron greeting, whole. */
    @Test
    void aSquadronMessageIsTheMessageAndNothingAroundIt() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphed(
                        message("squadron", "OLKI", "Nabend CMDRs o7"),
                        List.of(
                                NormalizedEventType.SYSTEM_ENTRY,
                                NormalizedEventType.of("RECEIVE_TEXT")
                        ),
                        true,
                        0
                )))
        )));

        assertEquals(
                List.of("events"),
                propertyNames(request),
                "no context, and no history either"
        );
        JsonNode event = request.path("events").get(0);
        assertEquals(
                List.of("event", "sender", "channel", "message"),
                propertyNames(event)
        );
        assertEquals(
                    "A text message was received.",
                    event.path("event").textValue());
        assertEquals("OLKI", event.path("sender").textValue());
        assertEquals("SQUADRON", event.path("channel").textValue());
        assertEquals(
                "Nabend CMDRs o7",
                event.path("message").textValue(),
                "the text itself is untouched"
        );
        assertFalse(request.has("trajectory"));
        assertFalse(
                request.toString().contains("SYSTEM_ENTERED"),
                "where the ship has been says nothing about a greeting"
        );
    }

    /**
     * The journal's own spelling never survives, whatever case it arrives in.
     *
     * <p>Only the projection changes: the raw payload still reads
     * {@code "Channel":"squadron"}, which is what the observation corpus and the
     * GUI are entitled to see.</p>
     */
    @Test
    void theChannelIsNormalizedWhateverCasingTheJournalUsed() {
        for (String written : List.of("squadron", "Squadron", "SQUADRON")) {
            DecisionTurnFixture fixture = new DecisionTurnFixture();
            ProjectedObservation observation = fixture.graphDisabled(
                    message(written, "OLKI", "o7")
            );
            String serialized = serializer.serialize(
                    factory.create(fixture.inputs(List.of(observation)))
            );

            assertTrue(
                    serialized.contains("\"channel\":\"SQUADRON\""),
                    written + " reached the model as " + serialized
            );
            assertFalse(serialized.contains("\"squadron\""));
            assertEquals(
                    written,
                    ((JournalEventObservation) observation.trigger().payload())
                            .raw()
                            .parsedJsonObject()
                            .path("Channel")
                            .textValue(),
                    "the raw journal event is not rewritten"
            );
        }
    }

    /** Every documented channel is a domain value, not a journal spelling. */
    @Test
    void everyKnownChannelHasADomainValue() {
        for (String channel : List.of(
                "wing",
                "local",
                "starsystem",
                "player",
                "friend",
                "direct",
                "voicechat"
        )) {
            DecisionTurnFixture fixture = new DecisionTurnFixture();
            JsonNode event = read(serializer.serialize(factory.create(
                    fixture.inputs(List.of(fixture.graphDisabled(
                            message(channel, "OLKI", "o7")
                    )))
            ))).path("events").get(0);

            assertEquals(
                    channel.toUpperCase(Locale.ROOT),
                    event.path("channel").textValue()
            );
        }
    }

    /**
     * A channel Kairon has not researched is named, not dropped.
     *
     * <p>The contract's casing still applies, so an unlisted channel cannot
     * introduce a second convention either.</p>
     */
    @Test
    void anUnknownChannelStillArrivesInTheContractsCasing() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode event = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphDisabled(
                        message("multicrew", "OLKI", "o7")
                )))
        ))).path("events").get(0);

        assertEquals("MULTICREW", event.path("channel").textValue());
    }

    /** A batch of nothing but messages is still a batch of nothing but talk. */
    @Test
    void severalMessagesInOneBatchAreAllNormalisedAndStillCarryNoHistory() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        List<NormalizedEventType> trajectory = List.of(
                NormalizedEventType.SYSTEM_ENTRY,
                NormalizedEventType.TOUCHDOWN,
                NormalizedEventType.of("RECEIVE_TEXT")
        );
        JsonNode request = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(
                        fixture.graphed(
                                message("squadron", "OLKI", "o7"),
                                trajectory,
                                false,
                                0
                        ),
                        fixture.graphed(
                                message("wing", "TESTCMDR", "on my way"),
                                trajectory,
                                true,
                                0
                        )
                ))
        )));

        List<String> channels = new ArrayList<>();
        request.path("events").forEach(event -> {
            assertEquals(
                    "A text message was received.",
                    event.path("event").textValue()
            );
            channels.add(event.path("channel").textValue());
        });
        assertEquals(List.of("SQUADRON", "WING"), channels);
        assertFalse(request.has("trajectory"));
    }

    /**
     * The rule is about the batch, not about the graph.
     *
     * <p>The identical episode produces a trajectory the moment the turn is
     * about something other than talking — so nothing was disabled, and the two
     * cases differ only in what the batch contains.</p>
     */
    @Test
    void aTurnThatIsNotOnlyTalkKeepsItsHistory() {
        List<NormalizedEventType> trajectory = List.of(
                NormalizedEventType.SYSTEM_ENTRY,
                NormalizedEventType.LIFTOFF,
                NormalizedEventType.TOUCHDOWN
        );

        DecisionTurnFixture alone = new DecisionTurnFixture();
        JsonNode landing = read(serializer.serialize(factory.create(
                alone.inputs(List.of(alone.graphed(
                        touchdown(),
                        trajectory,
                        true,
                        0
                )))
        )));
        assertEquals(
                List.of("SYSTEM_ENTERED", "LIFTOFF"),
                recent(landing),
                "an ordinary turn is unaffected"
        );

        DecisionTurnFixture mixed = new DecisionTurnFixture();
        JsonNode both = read(serializer.serialize(factory.create(
                mixed.inputs(List.of(
                        mixed.graphed(
                                message("squadron", "OLKI", "o7"),
                                trajectory,
                                false,
                                0
                        ),
                        mixed.graphed(touchdown(), trajectory, true, 0)
                ))
        )));
        assertEquals(
                List.of(
                        "A text message was received.",
                        "A ship landed on the surface of a planet or moon."
                ),
                descriptions(both)
        );
        assertEquals(
                List.of("SYSTEM_ENTERED", "LIFTOFF"),
                recent(both),
                "the landing is exactly what a history might explain"
        );
    }

    // ------------------------------------------------------------- fixtures

    private static String message(
            String channel,
            String sender,
            String text
    ) {
        return """
                {"timestamp":"2026-07-30T10:00:00Z","event":"ReceiveText",
                 "Channel":"%s","From":"%s","Message_Localised":"%s"}
                """.formatted(channel, sender, text);
    }

    private static String touchdown() {
        return """
                {"timestamp":"2026-07-30T10:00:01Z","event":"Touchdown",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "PlayerControlled":true,"OnStation":false,"OnPlanet":true}
                """;
    }

    private static List<String> recent(JsonNode request) {
        List<String> recent = new ArrayList<>();
        request.path("trajectory").path("recent")
                .forEach(kind -> recent.add(kind.textValue()));
        return List.copyOf(recent);
    }

    private static List<String> descriptions(JsonNode request) {
        List<String> descriptions = new ArrayList<>();
        request.path("events").forEach(event ->
                descriptions.add(event.path("event").textValue()));
        return List.copyOf(descriptions);
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
