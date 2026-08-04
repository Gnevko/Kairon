package kairon.observation.journal.event.travel;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code DockingGranted} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 4.5</a>
 */
public record DockingGranted(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "DockingGranted";

    public DockingGranted {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A docking request was granted.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String station = LlmPresentableJournalEvent
                .displayText(event, "StationName")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified station");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("LandingPad"))
                .ifPresent(value -> facts.add(
                        "assigned landing pad " + value
                ));
        LlmPresentableJournalEvent.textual(event.get("StationType"))
                .ifPresent(value -> facts.add(
                        "station type "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(value -> facts.add("market ID " + value));
        return new LlmEventPresentation(List.of(
                "The station "
                        + station
                        + " granted the player's docking request"
                        + (facts.isEmpty()
                        ? "."
                        : ", with "
                                + LlmPresentableJournalEvent.joinFacts(facts)
                                + ".")
        ));
    }
}
