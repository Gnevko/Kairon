package kairon.observation.journal.event.session;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code NewCommander} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 3.7</a>
 */
public record NewCommander(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "NewCommander";

    public NewCommander {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A new Commander was created.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String name = LlmPresentableJournalEvent.textual(event.get("Name"))
                .map(value -> "named "
                        + LlmPresentableJournalEvent.quoted(value))
                .orElse("with an unreported name");
        StringBuilder sentence = new StringBuilder(
                "A new commander was created, "
        ).append(name);
        LlmPresentableJournalEvent.displayText(event, "Package")
                .ifPresent(value -> sentence
                        .append(" using starter package ")
                        .append(LlmPresentableJournalEvent.quoted(value)));
        sentence.append(
                "; the account identifier is intentionally omitted from "
                        + "the model presentation."
        );
        return new LlmEventPresentation(List.of(sentence.toString()));
    }
}
