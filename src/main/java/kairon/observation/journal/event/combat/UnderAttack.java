package kairon.observation.journal.event.combat;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;
import java.util.Locale;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code UnderAttack} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 5.17</a>
 */
public record UnderAttack(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "UnderAttack";

    public UnderAttack {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A vessel is being fired upon.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String target = LlmPresentableJournalEvent.textual(
                        event.get("Target")
                )
                .map(UnderAttack::targetDescription)
                .orElse("an unspecified player-controlled target");
        return new LlmEventPresentation(List.of(
                "The journal reports that " + target + " is under fire.",
                "This event does not identify the attacker or report damage."
        ));
    }

    private static String targetDescription(String target) {
        return switch (target.toLowerCase(Locale.ROOT)) {
            case "fighter" -> "the ship-launched fighter";
            case "mothership" -> "the mothership";
            case "you" -> "the player";
            default -> "target "
                    + LlmPresentableJournalEvent.quoted(target);
        };
    }
}
