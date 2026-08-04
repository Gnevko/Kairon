package kairon.observer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.event.social.ReceiveText;
import kairon.observation.journal.event.travel.Touchdown;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which text messages are worth a turn, and which are ambient noise.
 *
 * <p>Admission is decided on the {@code Channel} field alone. Deciding on the
 * message text or a localised rendering would make what Kairon comments on
 * depend on the game's display language, which is not a property of the
 * situation.</p>
 */
final class NpcChatterSelectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void npcChatterIsNotEligibleForAModelTurn() {
        assertFalse(LlmJournalEventSelection.admitsAsTrigger(
                receiveText("npc", "Включенный канал: Schieni GG-A c3-84")
        ));
    }

    /**
     * The type stays NEW-eligible; only this observation is declined.
     *
     * <p>Selection by type is unchanged, so the event is still parsed,
     * projected, traced and shown in the GUI.</p>
     */
    @Test
    void theTypeItselfRemainsNewEligible() {
        assertEquals(
                LlmJournalEventSelection.ObserverInputRole.NEW_ELIGIBLE,
                LlmJournalEventSelection.roleOf(ReceiveText.class)
        );
        assertTrue(LlmJournalEventSelection.TARGET_NEW_ELIGIBLE
                .contains(ReceiveText.class));
        assertEquals(112, LlmJournalEventSelection.NEW_EVENT_TYPE_COUNT);
    }

    @Test
    void everyOtherChannelKeepsItsCurrentBehaviour() {
        for (String channel : List.of(
                "squadron",
                "local",
                "wing",
                "direct",
                "player",
                "friend",
                "starsystem",
                "voicechat"
        )) {
            assertTrue(
                    LlmJournalEventSelection.admitsAsTrigger(
                            receiveText(channel, "Nabend CMDRs o7")
                    ),
                    channel + " must be unaffected"
            );
        }
    }

    /** A message with no channel at all is not assumed to be chatter. */
    @Test
    void anAbsentOrNonTextualChannelIsStillAdmitted() throws Exception {
        assertTrue(LlmJournalEventSelection.admitsAsTrigger(new ReceiveText(
                raw("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"ReceiveText","From":"OLKI",
                         "Message":"o7"}
                        """)
        )));
        assertTrue(LlmJournalEventSelection.admitsAsTrigger(new ReceiveText(
                raw("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"ReceiveText","Channel":7,"From":"OLKI",
                         "Message":"o7"}
                        """)
        )));
    }

    /** Casing is not a channel: the rule is about which channel it is. */
    @Test
    void theChannelIsMatchedRegardlessOfCasingAndPadding() {
        for (String spelling : List.of("npc", "NPC", "Npc", " npc ")) {
            assertFalse(
                    LlmJournalEventSelection.admitsAsTrigger(
                            receiveText(spelling, "traffic control")
                    ),
                    spelling + " is the NPC channel"
            );
        }
    }

    /** Every other event type is admitted without inspecting its payload. */
    @Test
    void noOtherEventTypeIsFiltered() throws Exception {
        JournalEventObservation touchdown = new Touchdown(raw("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Touchdown",
                 "PlayerControlled":true,"Latitude":18.7,"Longitude":-35.0}
                """));
        assertTrue(LlmJournalEventSelection.admitsAsTrigger(touchdown));
    }

    private static ReceiveText receiveText(String channel, String message) {
        try {
            return new ReceiveText(raw("""
                    {"timestamp":"2026-07-30T10:00:00Z",
                     "event":"ReceiveText","Channel":"%s","From":"OLKI",
                     "Message":"%s"}
                    """.formatted(channel, message)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static RawJournalData raw(String rawJson) throws Exception {
        String compact = JSON.readTree(rawJson).toString();
        return new RawJournalData(
                compact,
                JSON.readTree(compact),
                Optional.of("ReceiveText").filter(
                        ignored -> compact.contains("\"ReceiveText\"")
                ).or(() -> Optional.of("Touchdown")),
                Optional.of(Instant.parse("2026-07-30T10:00:00Z"))
        );
    }
}
