package kairon.observation.journal.event.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;
import java.util.Optional;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code MaterialCollected} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.8</a>
 */
public record MaterialCollected(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "MaterialCollected";

    public MaterialCollected {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String material = LlmPresentableJournalEvent
                .displayText(event, "Name")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified material");
        Optional<Long> count = LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("Count"));
        Optional<String> category = LlmPresentableJournalEvent
                .displayText(event, "Category");
        return new LlmEventPresentation(List.of(
                "The player collected "
                        + count.map(value -> value + " units of ")
                                .orElse("")
                        + "material "
                        + material
                        + category.map(value ->
                                " in journal category "
                                        + LlmPresentableJournalEvent.quoted(
                                                value
                                        ))
                                .orElse("")
                        + "."
        ));
    }
}
