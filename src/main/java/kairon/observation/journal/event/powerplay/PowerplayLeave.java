package kairon.observation.journal.event.powerplay;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code PowerplayLeave} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 9.6</a>
 */
public record PowerplayLeave(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "PowerplayLeave";

    public PowerplayLeave {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String power = LlmPresentableJournalEvent.textual(event.get("Power"))
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unnamed power");
        return new LlmEventPresentation(List.of(
                "The player left Powerplay power "
                        + power
                        + "; this is a departure, not a recorded defection "
                        + "to another power."
        ));
    }
}
