package kairon.observation.journal.event.carrier;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code CarrierCancelDecommission} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 11.6</a>
 */
public record CarrierCancelDecommission(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "CarrierCancelDecommission";

    public CarrierCancelDecommission {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A fleet carrier decommission was cancelled.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder sentence = new StringBuilder(
                "The player cancelled a pending fleet-carrier "
                        + "decommissioning request"
        );
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("CarrierID"))
                .ifPresent(carrierId -> sentence
                        .append(" for carrier market ID ")
                        .append(carrierId));
        sentence.append('.');
        return new LlmEventPresentation(List.of(sentence.toString()));
    }
}
