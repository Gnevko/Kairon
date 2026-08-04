package kairon.observation.journal.event.carrier;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code CarrierBuy} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 11.2</a>
 */
public record CarrierBuy(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "CarrierBuy";

    public CarrierBuy {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A fleet carrier was purchased.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        java.util.List<String> sentences = new java.util.ArrayList<>();

        StringBuilder purchase = new StringBuilder(
                "The player bought a fleet carrier"
        );
        LlmPresentableJournalEvent.textual(event.get("Callsign"))
                .ifPresent(callsign -> purchase
                        .append(" with callsign ")
                        .append(LlmPresentableJournalEvent.quoted(callsign)));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("CarrierID"))
                .ifPresent(carrierId -> purchase
                        .append(" (carrier market ID ")
                        .append(carrierId)
                        .append(')'));
        LlmPresentableJournalEvent.textual(event.get("Location"))
                .ifPresent(location -> purchase
                        .append(" in star system ")
                        .append(LlmPresentableJournalEvent.quoted(location)));
        purchase.append('.');
        sentences.add(purchase.toString());

        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("Price"))
                .ifPresent(price -> sentences.add(
                        "The journal reports a purchase price of "
                                + LlmPresentableJournalEvent
                                        .formattedInteger(price)
                                + " credits."
                ));

        java.util.List<String> sourceReferences = new java.util.ArrayList<>();
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("BoughtAtMarket"))
                .ifPresent(marketId -> sourceReferences.add(
                        "purchase market ID " + marketId
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("SystemAddress"))
                .ifPresent(systemAddress -> sourceReferences.add(
                        "star-system address " + systemAddress
                ));
        if (!sourceReferences.isEmpty()) {
            sentences.add(
                    "The purchase record identifies "
                            + LlmPresentableJournalEvent.joinFacts(
                                    sourceReferences
                            )
                            + "."
            );
        }

        LlmPresentableJournalEvent.textual(event.get("Variant"))
                .ifPresent(variant -> sentences.add(
                        "The journal's carrier variant identifier is "
                                + LlmPresentableJournalEvent.quoted(variant)
                                + "; the identifier alone does not describe "
                                + "the variant's capabilities."
                ));
        return new LlmEventPresentation(sentences);
    }
}
