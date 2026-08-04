package kairon.observation.journal.event.session;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code Commander} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 3.3</a>
 */
public record Commander(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Commander";

    public Commander {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "The game session being loaded identified its Commander.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String commander = LlmPresentableJournalEvent
                .textual(event.get("Name"))
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("whose name was not supplied");

        return new LlmEventPresentation(List.of(
                "A new game-loading session started for the current player, "
                        + "Commander "
                        + commander
                        + ".",
                "The journal writes this commander identity before the "
                        + "player's inventory and ship loadout."
        ));
    }
}
