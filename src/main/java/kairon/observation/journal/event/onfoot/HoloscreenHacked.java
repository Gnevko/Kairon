package kairon.observation.journal.event.onfoot;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code HoloscreenHacked} journal event.
 *
 * @see <a href="https://github.com/jixxed/ed-journal-schemas/blob/33a8f35e81868b168b4bbd647b5e13dbd8de062a/schemas/HoloscreenHacked/HoloscreenHacked.json">
 * Pinned journal schema and observed-field descriptions</a>
 */
public record HoloscreenHacked(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "HoloscreenHacked";

    public HoloscreenHacked {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        var before = LlmPresentableJournalEvent.textual(
                event.get("PowerBefore")
        );
        var after = LlmPresentableJournalEvent.textual(
                event.get("PowerAfter")
        );
        String sentence;
        if (before.isPresent() && after.isPresent()) {
            sentence = "The player hacked a holo-screen, changing its "
                    + "recorded Power owner from "
                    + LlmPresentableJournalEvent.quoted(before.get())
                    + " to "
                    + LlmPresentableJournalEvent.quoted(after.get())
                    + ".";
        } else if (after.isPresent()) {
            sentence = "The player hacked a holo-screen; its recorded "
                    + "Power owner afterward is "
                    + LlmPresentableJournalEvent.quoted(after.get())
                    + ", while the previous owner is not reported.";
        } else {
            sentence = "The player hacked a holo-screen, but this event "
                    + "does not provide a usable resulting Power owner.";
        }
        return new LlmEventPresentation(List.of(sentence));
    }
}
