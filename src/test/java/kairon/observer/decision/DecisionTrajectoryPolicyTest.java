package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.journal.event.social.Friends;
import kairon.observation.journal.event.social.ReceiveText;
import kairon.observer.decision.DecisionTurnFixture.TrajectoryEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which turns are about where the ship has been, and which are not.
 *
 * <p>One policy, one closed set of kinds, and the whole of it decided on the
 * projected {@code kind} values. A friend coming online happens to a person
 * somewhere else; a received message says what it says. Neither is easier to
 * read for knowing that the ship landed first, and a forecast of the next
 * manoeuvre beside one of them invites a connection that is not there.</p>
 *
 * <p>The suppression is a projection rule and nothing more: the episode still
 * advances, the occurrence is still recorded and the prediction is still
 * calculated for every one of these turns.</p>
 */
final class DecisionTrajectoryPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** An episode long enough that a trajectory would certainly be sent. */
    private static final List<NormalizedEventType> EPISODE = List.of(
            NormalizedEventType.SYSTEM_ENTRY,
            NormalizedEventType.LIFTOFF,
            NormalizedEventType.TOUCHDOWN
    );

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /**
     * The set is the policy, and it is spelled the way the catalogue spells it.
     *
     * <p>A kind listed here under a spelling no event actually produces would
     * suppress nothing and say so nowhere.</p>
     */
    @Test
    void thePolicyIsAClosedSetOfCataloguedKinds() {
        assertEquals(
                Set.of(
                        DecisionEventCatalog.ruleFor(ReceiveText.class).kind(),
                        DecisionEventCatalog.ruleFor(Friends.class).kind()
                ),
                DecisionTrajectoryProjector.trajectoryIndependentKinds()
        );
        assertEquals(
                Set.of("MESSAGE_RECEIVED", "FRIEND_STATUS"),
                DecisionTrajectoryProjector.trajectoryIndependentKinds(),
                "growing this set is a claim about an event kind"
        );
    }

    /** One friend notification, and nothing about the ship around it. */
    @Test
    void aSingleFriendNotificationCarriesNoFlightHistory() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(
                        fixture.graphed(friends("KotyaGaw"), EPISODE, true, 0)
                ))
        )));

        assertEquals(List.of("events"), propertyNames(request));
        JsonNode event = request.path("events").get(0);
        assertEquals(
                List.of("event", "friend", "status"),
                propertyNames(event),
                "the event itself is exactly what it was"
        );
        assertEquals(
                    "Information about a friend's status was received.",
                    event.path("event").textValue());
        assertEquals("KotyaGaw", event.path("friend").textValue());
        assertEquals("ONLINE", event.path("status").textValue());
        assertFalse(request.has("trajectory"));
    }

    /** The measured opening turn of the replay, whole. */
    @Test
    void twoFriendNotificationsKeepTheirIdsAndStillCarryNoHistory() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(
                        fixture.graphed(
                                friends("KotyaGaw"),
                                EPISODE,
                                false,
                                0
                        ),
                        fixture.graphed(
                                friends("Alysianfolly"),
                                EPISODE,
                                true,
                                0
                        )
                ))
        )));

        assertEquals(List.of("events"), propertyNames(request));
        assertEquals(2, request.path("events").size());
        assertEquals(
                List.of("KotyaGaw", "Alysianfolly"),
                values(request, "friend")
        );
        assertEquals(List.of("ONLINE", "ONLINE"), values(request, "status"));
        assertFalse(request.has("trajectory"));
        assertFalse(
                request.toString().contains("loginTransition"),
                "nothing retired comes back with this change"
        );
        assertFalse(request.toString().contains("newlyOnline"));
        assertFalse(request.toString().contains("UNCONFIRMED"));
    }

    /**
     * Both notifications are still two events.
     *
     * <p>This rule is about the flight history, not about repetition: two
     * friends coming online at once stay two entries in the array, and a second
     * identical status is never folded into the first.</p>
     */
    @Test
    void anIdenticalRepeatedStatusIsStillItsOwnEvent() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(
                        fixture.graphDisabled(friends("KotyaGaw")),
                        fixture.graphDisabled(friends("KotyaGaw"))
                ))
        )));

        assertEquals(2, request.path("events").size());
        assertEquals(
                List.of("KotyaGaw", "KotyaGaw"),
                values(request, "friend")
        );
        for (JsonNode event : request.path("events")) {
            assertFalse(event.has("id"));
        }
    }

    /** Everything in the batch is off the flight path, so the rule applies. */
    @Test
    void aBatchOfOnlyTalkAndFriendsCarriesNoHistoryEither() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(
                        fixture.graphed(
                                friends("KotyaGaw"),
                                EPISODE,
                                false,
                                0
                        ),
                        fixture.graphed(message(), EPISODE, true, 0)
                ))
        )));

        assertEquals(
                List.of(
                        "Information about a friend's status was received.",
                        "A text message was received."
                ),
                descriptions(request)
        );
        assertFalse(request.has("trajectory"));
    }

    /**
     * One friend notification does not silence a landing.
     *
     * <p>The landing is exactly the event a history might explain, so the batch
     * keeps everything it would have had on its own.</p>
     */
    @Test
    void aMixedBatchWithAFlightEventKeepsItsHistory() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(
                        fixture.graphed(
                                friends("KotyaGaw"),
                                EPISODE,
                                false,
                                0
                        ),
                        fixture.graphed(touchdown(), EPISODE, true, 0)
                ))
        )));

        assertEquals(
                List.of(
                        "Information about a friend's status was received.",
                        "A ship landed on the surface of a planet or moon."
                ),
                descriptions(request)
        );
        assertEquals(
                List.of("SYSTEM_ENTERED", "LIFTOFF"),
                recent(request),
                "the same history the landing would have had alone"
        );
    }

    /** An ordinary turn is untouched, predictions included. */
    @Test
    void aFlightEventAloneKeepsBothHalvesOfItsTrajectory() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphedPredicting(
                        touchdown(),
                        episodeEntries(),
                        List.of(NormalizedEventType.DISEMBARK)
                )))
        )));

        assertEquals(
                List.of("SYSTEM_ENTERED", "LIFTOFF"),
                recent(request)
        );
        JsonNode likelyNext =
                request.path("trajectory").path("likelyNext").get(0);
        assertEquals("DISEMBARKED", likelyNext.path("kind").textValue());
        assertEquals(
                1.0,
                likelyNext.path("probability").doubleValue(),
                0.0
        );
    }

    /** A suppressed trajectory is gone, not emptied. */
    @Test
    void aSuppressedTrajectoryLeavesNoTraceInTheJson() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        String serialized = serializer.serialize(factory.create(
                fixture.inputs(List.of(
                        fixture.graphedPredicting(
                                friends("KotyaGaw"),
                                episodeEntries(),
                                List.of(NormalizedEventType.DISEMBARK)
                        )
                ))
        ));

        for (String absent : List.of(
                "trajectory",
                "recent",
                "likelyNext",
                "{}",
                "[]",
                "null"
        )) {
            assertFalse(
                    serialized.contains(absent),
                    absent + " survived: " + serialized
            );
        }
        assertTrue(serialized.startsWith("{\"events\":["));
    }

    // ------------------------------------------------------------- fixtures

    private static List<TrajectoryEntry> episodeEntries() {
        List<TrajectoryEntry> entries = new ArrayList<>(EPISODE.size());
        EPISODE.forEach(type -> entries.add(TrajectoryEntry.journal(type)));
        return List.copyOf(entries);
    }

    private static String friends(String name) {
        return """
                {"timestamp":"2026-07-30T10:00:00Z","event":"Friends",
                 "Status":"Online","Name":"%s"}
                """.formatted(name);
    }

    private static String message() {
        return """
                {"timestamp":"2026-07-30T10:00:01Z","event":"ReceiveText",
                 "Channel":"squadron","From":"OLKI",
                 "Message_Localised":"Nabend CMDRs o7"}
                """;
    }

    private static String touchdown() {
        return """
                {"timestamp":"2026-07-30T10:00:02Z","event":"Touchdown",
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
        return values(request, "event");
    }

    private static List<String> values(JsonNode request, String field) {
        List<String> values = new ArrayList<>();
        request.path("events")
                .forEach(event -> values.add(event.path(field).textValue()));
        return List.copyOf(values);
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
