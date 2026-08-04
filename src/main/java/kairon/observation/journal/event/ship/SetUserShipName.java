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
 * {@code SetUserShipName} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.45</a>
 */
public record SetUserShipName(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "SetUserShipName";

    public SetUserShipName {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String name = LlmPresentableJournalEvent
                .textual(event.get("UserShipName"))
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified name");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "Ship")
                .ifPresent(value -> facts.add(
                        "ship type "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("ShipID"))
                .ifPresent(value -> facts.add("ship ID " + value));
        LlmPresentableJournalEvent.textual(event.get("UserShipId"))
                .ifPresent(value -> facts.add(
                        "user-assigned ship identifier "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        String sentence = "In Starport Services, the player assigned "
                + "the ship name "
                + name
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
