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
 * {@code ShipyardTransfer} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.50</a>
 */
public record ShipyardTransfer(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ShipyardTransfer";

    public ShipyardTransfer {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "The Commander requested a stored ship be transported here.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String ship = LlmPresentableJournalEvent
                .displayText(event, "ShipType")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified ship");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("ShipID"))
                .ifPresent(value -> facts.add("ship ID " + value));
        LlmPresentableJournalEvent.textual(event.get("System"))
                .ifPresent(value -> facts.add(
                        "origin system "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("ShipMarketID"))
                .ifPresent(value -> facts.add(
                        "origin market ID " + value
                ));
        LlmPresentableJournalEvent.decimal(event.get("Distance"))
                .ifPresent(value -> facts.add(
                        "transfer distance " + value + " light-years"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("TransferPrice"))
                .ifPresent(value -> facts.add(
                        "transfer cost "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
                                + " credits"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("TransferTime"))
                .ifPresent(value -> facts.add(
                        "transfer time " + value + " seconds"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(value -> facts.add(
                        "destination market ID " + value
                ));
        String sentence = "The player requested transport of ship "
                + ship
                + " from another station to the current station"
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
