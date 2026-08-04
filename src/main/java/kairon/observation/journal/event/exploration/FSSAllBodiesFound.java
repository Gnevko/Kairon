package kairon.observation.journal.event.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code FSSAllBodiesFound} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.4</a>
 */
public record FSSAllBodiesFound(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "FSSAllBodiesFound";

    public FSSAllBodiesFound {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "All bodies in the star system have been identified.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder sentence = new StringBuilder(
                "The player identified all bodies in a star system"
        );
        LlmPresentableJournalEvent.textual(event.get("SystemName"))
                .ifPresent(system -> sentence
                        .append(", ")
                        .append(LlmPresentableJournalEvent.quoted(system)));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("Count"))
                .ifPresent(count -> sentence
                        .append("; the system contains ")
                        .append(LlmPresentableJournalEvent
                                .formattedInteger(count))
                        .append(" identified bod")
                        .append(count == 1 ? "y" : "ies"));
        sentence.append('.');
        return new LlmEventPresentation(List.of(sentence.toString()));
    }
}
