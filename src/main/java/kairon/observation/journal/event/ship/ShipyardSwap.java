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
 * {@code ShipyardSwap} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.51</a>
 */
public record ShipyardSwap(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ShipyardSwap";

    public ShipyardSwap {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String selectedShip = LlmPresentableJournalEvent
                .displayText(event, "ShipType")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified stored ship");
        List<String> selectedFacts = new ArrayList<>();
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("ShipID"))
                .ifPresent(value -> selectedFacts.add(
                        "selected ship ID " + value
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(value -> selectedFacts.add(
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

        List<String> sentences = new ArrayList<>();
        sentences.add(
                "At the current shipyard, the player switched to stored "
                        + "ship "
                        + selectedShip
                        + (selectedFacts.isEmpty()
                        ? "."
                        : ", with "
                                + LlmPresentableJournalEvent.joinFacts(
                                        selectedFacts
                                )
                                + ".")
        );
        if (!previousShipFacts.isEmpty()) {
            sentences.add(
                    "The same switch reports that "
                            + LlmPresentableJournalEvent.joinFacts(
                                    previousShipFacts
                            )
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
