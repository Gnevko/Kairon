package kairon.observation.journal.event.ship;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code ShipyardSell} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.49</a>
 */
public record ShipyardSell(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ShipyardSell";

    public ShipyardSell {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A stored ship was sold at a shipyard.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String ship = LlmPresentableJournalEvent
                .displayText(event, "ShipType")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified ship type");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("SellShipID"))
                .ifPresent(value -> facts.add("stored ship ID " + value));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("ShipPrice"))
                .ifPresent(value -> facts.add(
                        "sale price "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
                                + " credits"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(value -> facts.add(
                        "sale market ID " + value
                ));
        LlmPresentableJournalEvent.textual(event.get("System"))
                .ifPresent(value -> facts.add(
                        "the stored ship was in system "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("ShipMarketID"))
                .ifPresent(value -> facts.add(
                        "stored-ship market ID " + value
                ));
        String sentence = "The player sold stored ship "
                + ship
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
