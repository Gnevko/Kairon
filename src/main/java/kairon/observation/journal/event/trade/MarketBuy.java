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
 * {@code MarketBuy} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 7.5</a>
 */
public record MarketBuy(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "MarketBuy";

    public MarketBuy {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String commodity = LlmPresentableJournalEvent
                .displayText(event, "Type")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified commodity");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("Count"))
                .ifPresent(value -> facts.add(
                        LlmPresentableJournalEvent.formattedInteger(value)
                                + " units"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("BuyPrice"))
                .ifPresent(value -> facts.add(
                        "unit price "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
                                + " credits"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("TotalCost"))
                .ifPresent(value -> facts.add(
                        "total cost "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
                                + " credits"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(value -> facts.add("market ID " + value));
        String sentence = "The player bought commodity "
                + commodity
                + " at a market"
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
