package kairon.observation.journal.event.carrier;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code CarrierDecommission} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 11.5</a>
 */
public record CarrierDecommission(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "CarrierDecommission";

    public CarrierDecommission {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "Decommissioning of a fleet carrier was requested.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();

        StringBuilder request = new StringBuilder(
                "The player requested decommissioning of a fleet carrier"
        );
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("CarrierID"))
                .ifPresent(carrierId -> request
                        .append(" with carrier market ID ")
                        .append(carrierId));
        request.append("; this event records the request, not completed "
                + "decommissioning.");
        sentences.add(request.toString());

        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("ScrapRefund"))
                .ifPresent(refund -> sentences.add(
                        "The reported refund after decommissioning is "
                                + LlmPresentableJournalEvent
                                        .formattedInteger(refund)
                                + " credits."
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("ScrapTime"))
                .ifPresent(scrapTime -> sentences.add(
                        "The journal reports the scheduled scrap time as "
                                + "numeric source timestamp "
                                + scrapTime
                                + "; the cited Journal Manual does not "
                                + "define that number's epoch or unit."
                ));
        return new LlmEventPresentation(sentences);
    }
}
