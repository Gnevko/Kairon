package kairon.observation.journal.event.trade;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code SearchAndRescue} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.42</a>
 */
public record SearchAndRescue(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "SearchAndRescue";

    public SearchAndRescue {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String item = LlmPresentableJournalEvent
                .displayText(event, "Name")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified recovered item");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("Count"))
                .ifPresent(value -> facts.add(
                        LlmPresentableJournalEvent.formattedInteger(value)
                                + " items delivered"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("Reward"))
                .ifPresent(value -> facts.add(
                        "reward "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
                                + " credits"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(value -> facts.add(
                        "contact market ID " + value
                ));
        String sentence = "The player delivered "
                + item
                + " to a Search and Rescue contact"
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
