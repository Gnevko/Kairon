package kairon.observation.journal.event.mining;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code AsteroidCracked} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 7.1</a>
 */
public record AsteroidCracked(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "AsteroidCracked";

    public AsteroidCracked {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A motherlode asteroid was broken up for mining.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder sentence = new StringBuilder(
                "The player broke open a motherlode asteroid for mining"
        );
        LlmPresentableJournalEvent.textual(event.get("Body"))
                .ifPresent(body -> sentence
                        .append(" near body ")
                        .append(LlmPresentableJournalEvent.quoted(body)));
        sentence.append(
                "; this event does not identify the asteroid's contents."
        );
        return new LlmEventPresentation(List.of(sentence.toString()));
    }
}
