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
 * {@code ShipyardBuy} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.47</a>
 */
public record ShipyardBuy(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ShipyardBuy";

    public ShipyardBuy {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String ship = LlmPresentableJournalEvent
                .displayText(event, "ShipType")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified ship type");
        List<String> purchaseFacts = new ArrayList<>();
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("ShipPrice"))
                .ifPresent(value -> purchaseFacts.add(
                        "purchase price "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
                                + " credits"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(value -> purchaseFacts.add(
                        "shipyard market ID " + value
                ));

        List<String> previousShipFacts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "StoreOldShip")
                .ifPresent(value -> previousShipFacts.add(
                        "the previous ship "
                                + LlmPresentableJournalEvent.quoted(value)
                                + " was stored"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("StoreShipID"))
                .ifPresent(value -> previousShipFacts.add(
                        "stored ship ID " + value
                ));
        LlmPresentableJournalEvent.displayText(event, "SellOldShip")
                .ifPresent(value -> previousShipFacts.add(
                        "the previous ship "
                                + LlmPresentableJournalEvent.quoted(value)
                                + " was sold"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("SellShipID"))
                .ifPresent(value -> previousShipFacts.add(
                        "sold ship ID " + value
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("SellPrice"))
                .ifPresent(value -> previousShipFacts.add(
                        "previous-ship sale price "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
                                + " credits"
                ));

        List<String> sentences = new ArrayList<>();
        sentences.add(
                "The player bought a new ship of type "
                        + ship
                        + (purchaseFacts.isEmpty()
                        ? "."
                        : ", with "
                                + LlmPresentableJournalEvent.joinFacts(
                                        purchaseFacts
                                )
                                + ".")
        );
        if (!previousShipFacts.isEmpty()) {
            sentences.add(
                    "The same transaction reports that "
                            + LlmPresentableJournalEvent.joinFacts(
                                    previousShipFacts
                            )
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
