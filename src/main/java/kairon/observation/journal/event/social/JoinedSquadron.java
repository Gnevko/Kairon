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
 * {@code JoinedSquadron} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 10.4</a>
 */
public record JoinedSquadron(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "JoinedSquadron";

    public JoinedSquadron {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String squadron = LlmPresentableJournalEvent
                .textual(event.get("SquadronName"))
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unnamed squadron");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("SquadronID"))
                .ifPresent(value -> facts.add("squadron ID " + value));
        String sentence = "The player joined squadron "
                + squadron
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
