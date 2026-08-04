package kairon.observation.journal.event.powerplay;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code PowerplayDefect} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 9.2</a>
 */
public record PowerplayDefect(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "PowerplayDefect";

    public PowerplayDefect {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        var from = LlmPresentableJournalEvent.textual(
                event.get("FromPower")
        );
        var to = LlmPresentableJournalEvent.textual(event.get("ToPower"));
        String sentence;
        if (from.isPresent() && to.isPresent()) {
            sentence = "The player defected in Powerplay from "
                    + LlmPresentableJournalEvent.quoted(from.get())
                    + " to "
                    + LlmPresentableJournalEvent.quoted(to.get())
                    + ".";
        } else {
            sentence = "The player defected from one Powerplay power to "
                    + "another; the event does not provide both usable "
                    + "power names.";
        }
        return new LlmEventPresentation(List.of(sentence));
    }
}
