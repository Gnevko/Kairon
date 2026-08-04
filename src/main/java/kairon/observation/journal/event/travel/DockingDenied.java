package kairon.observation.journal.event.travel;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code DockingDenied} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 4.4</a>
 */
public record DockingDenied(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "DockingDenied";

    public DockingDenied {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A station denied a docking request.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String station = LlmPresentableJournalEvent
                .displayText(event, "StationName")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified station");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("Reason"))
                .ifPresent(value -> facts.add(
                        "reported reason: " + reasonDescription(value)
                ));
        LlmPresentableJournalEvent.textual(event.get("StationType"))
                .ifPresent(value -> facts.add(
                        "station type "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(value -> facts.add("market ID " + value));
        String sentence = "The station "
                + station
                + " denied the player's docking request"
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }

    private static String reasonDescription(String reason) {
        return switch (reason.toLowerCase(Locale.ROOT)) {
            case "nospace" -> "no landing space";
            case "toolarge" -> "the ship is too large";
            case "hostile" -> "hostile status";
            case "offences" -> "recorded offences";
            case "distance" -> "the ship is too far away";
            case "activefighter" -> "a ship-launched fighter is active";
            case "noreason" -> "no specific reason";
            default -> "source reason "
                    + LlmPresentableJournalEvent.quoted(reason);
        };
    }
}
