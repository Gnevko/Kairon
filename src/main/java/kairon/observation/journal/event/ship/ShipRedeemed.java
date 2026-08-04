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
 * {@code ShipRedeemed} journal event.
 *
 * @see <a href="https://github.com/jixxed/ed-journal-schemas/blob/33a8f35e81868b168b4bbd647b5e13dbd8de062a/schemas/ShipRedeemed/ShipRedeemed.json">
 * Pinned Elite Dangerous journal schema for ShipRedeemed</a>
 */
public record ShipRedeemed(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ShipRedeemed";

    public ShipRedeemed {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "ShipType")
                .ifPresent(value -> facts.add(
                        "ship type "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("NewShipID"))
                .ifPresent(value -> facts.add("new ship ID " + value));
        String sentence = "A new ship was redeemed"
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
