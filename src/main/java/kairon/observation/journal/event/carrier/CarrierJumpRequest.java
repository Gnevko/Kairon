package kairon.observation.journal.event.carrier;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code CarrierJumpRequest} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 11.4</a>
 */
public record CarrierJumpRequest(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "CarrierJumpRequest";

    public CarrierJumpRequest {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A fleet carrier jump was scheduled.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        java.util.List<String> destinationFacts =
                new java.util.ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("SystemName"))
                .ifPresent(system -> destinationFacts.add(
                        "star system "
                                + LlmPresentableJournalEvent.quoted(system)
                ));
        LlmPresentableJournalEvent.textual(event.get("Body"))
                .ifPresent(body -> destinationFacts.add(
                        "body " + LlmPresentableJournalEvent.quoted(body)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("BodyID"))
                .ifPresent(bodyId -> destinationFacts.add(
                        "body ID " + bodyId
                ));

        StringBuilder request = new StringBuilder(
                "The player requested a fleet-carrier jump"
        );
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("CarrierID"))
                .ifPresent(carrierId -> request
                        .append(" for carrier market ID ")
                        .append(carrierId));
        if (!destinationFacts.isEmpty()) {
            request.append(" to ")
                    .append(LlmPresentableJournalEvent.joinFacts(
                            destinationFacts
                    ));
        }
        request.append("; this event records the request, not the completed "
                + "jump.");

        java.util.List<String> sentences = new java.util.ArrayList<>();
        sentences.add(request.toString());
        LlmPresentableJournalEvent.textual(event.get("DepartureTime"))
                .ifPresent(departureTime -> sentences.add(
                        "The requested departure time is "
                                + LlmPresentableJournalEvent.quoted(
                                        departureTime
                                )
                                + "."
                ));

        java.util.Optional<Long> systemAddress =
                LlmPresentableJournalEvent.nonNegativeIntegral(
                        event.get("SystemAddress")
                );
        if (systemAddress.isEmpty()) {
            systemAddress = LlmPresentableJournalEvent.nonNegativeIntegral(
                    event.get("SystemID")
            );
        }
        systemAddress.ifPresent(address -> sentences.add(
                "The destination star-system address is " + address + "."
        ));
        return new LlmEventPresentation(sentences);
    }
}
