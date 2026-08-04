package kairon.observation.journal.event.social;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code CrewMemberQuits} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.9</a>
 */
public record CrewMemberQuits(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "CrewMemberQuits";

    public CrewMemberQuits {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String commander = LlmPresentableJournalEvent
                .textual(event.get("Crew"))
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unnamed commander");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent
                .booleanValue(event.get("Telepresence"))
                .ifPresent(value -> facts.add(
                        value
                                ? "the journal marks the connection as "
                                        + "telepresence"
                                : "the journal marks the connection as "
                                        + "non-telepresence"
                ));
        String sentence = "Commander "
                + commander
                + " left the player's ship multicrew session"
                + (facts.isEmpty()
                ? "."
                : "; "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
