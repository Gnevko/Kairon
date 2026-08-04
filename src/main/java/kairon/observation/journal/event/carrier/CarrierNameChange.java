package kairon.observation.journal.event.carrier;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code CarrierNameChange} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 11.15</a>
 */
public record CarrierNameChange(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "CarrierNameChange";

    public CarrierNameChange {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A fleet carrier was renamed.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder sentence = new StringBuilder();
        LlmPresentableJournalEvent.textual(event.get("Name"))
                .ifPresentOrElse(
                        name -> sentence
                                .append("The player changed a fleet carrier's "
                                        + "name to ")
                                .append(LlmPresentableJournalEvent.quoted(name)),
                        () -> sentence.append(
                                "The journal recorded a fleet-carrier name "
                                        + "change"
                        )
                );
        LlmPresentableJournalEvent.textual(event.get("Callsign"))
                .ifPresent(callsign -> sentence
                        .append(" for callsign ")
                        .append(LlmPresentableJournalEvent.quoted(callsign)));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("CarrierID"))
                .ifPresent(carrierId -> sentence
                        .append(" (carrier market ID ")
                        .append(carrierId)
                        .append(')'));
        sentence.append("; the event does not report the previous name.");
        return new LlmEventPresentation(List.of(sentence.toString()));
    }
}
