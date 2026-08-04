package kairon.observation.journal.event.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code MaterialDiscovered} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.10</a>
 */
public record MaterialDiscovered(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "MaterialDiscovered";

    public MaterialDiscovered {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A new material was discovered.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String material = LlmPresentableJournalEvent
                .displayText(event, "Name")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified material");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "Category")
                .ifPresent(category -> facts.add(
                        "category "
                                + LlmPresentableJournalEvent.quoted(category)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("DiscoveryNumber"))
                .ifPresent(number -> facts.add(
                        "source discovery number "
                                + LlmPresentableJournalEvent
                                .formattedInteger(number)
                ));

        List<String> sentences = new ArrayList<>();
        sentences.add(
                "The player discovered a new material, "
                        + material
                        + "."
        );
        if (!facts.isEmpty()) {
            sentences.add(
                    "The discovery record reports "
                            + LlmPresentableJournalEvent.joinFacts(facts)
                            + "; the discovery number is not a rarity or "
                            + "quality rating."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
