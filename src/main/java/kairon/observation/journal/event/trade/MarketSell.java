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
 * {@code MarketSell} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 7.6</a>
 */
public record MarketSell(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "MarketSell";

    public MarketSell {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "Goods were sold in the market.";
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
        addCredits(event, "SellPrice", "unit sale price", facts);
        addCredits(event, "TotalSale", "total sale value", facts);
        addCredits(event, "AvgPricePaid", "average purchase price", facts);
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(value -> facts.add("market ID " + value));
        addFlag(event, "StolenGoods", "goods marked as stolen",
                "goods not marked as stolen", facts);
        addFlag(event, "IllegalGoods", "goods marked as illegal here",
                "goods not marked as illegal here", facts);
        addFlag(event, "BlackMarket", "sale made on the black market",
                "sale not marked as a black-market transaction", facts);
        String sentence = "The player sold commodity "
                + commodity
                + " at a market"
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }

    private static void addCredits(
            JsonNode event,
            String field,
            String label,
            List<String> facts
    ) {
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get(field))
                .ifPresent(value -> facts.add(
                        label
                                + " "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
                                + " credits"
                ));
    }

    private static void addFlag(
            JsonNode event,
            String field,
            String whenTrue,
            String whenFalse,
            List<String> facts
    ) {
        LlmPresentableJournalEvent.booleanValue(event.get(field))
                .ifPresent(value -> facts.add(
                        value ? whenTrue : whenFalse
                ));
    }
}
