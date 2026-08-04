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
 * {@code LeaveBody} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 4.10</a>
 */
public record LeaveBody(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "LeaveBody";

    public LeaveBody {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String body = LlmPresentableJournalEvent
                .textual(event.get("Body"))
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified planet");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("StarSystem"))
                .ifPresent(value -> facts.add(
                        "system "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("BodyID"))
                .ifPresent(value -> facts.add("body ID " + value));
        String sentence = "While flying away from planet "
                + body
                + ", the ship rose above the orbital-cruise altitude"
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
