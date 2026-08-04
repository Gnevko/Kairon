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
 * {@code SellShipOnRebuy} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.44</a>
 */
public record SellShipOnRebuy(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "SellShipOnRebuy";

    public SellShipOnRebuy {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A stored ship was sold to raise funds on the rebuy screen.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String ship = LlmPresentableJournalEvent
                .displayText(event, "ShipType")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified stored ship");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("System"))
                .ifPresent(value -> facts.add(
                        "stored in system "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("ShipPrice"))
                .ifPresent(value -> facts.add(
                        "sale price "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
                                + " credits"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("SellShipId"))
                .ifPresent(value -> facts.add("stored ship ID " + value));
        String sentence = "On the insurance rebuy screen, the player sold "
                + "stored ship "
                + ship
                + " to raise funds"
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
