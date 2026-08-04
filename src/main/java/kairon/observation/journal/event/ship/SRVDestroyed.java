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
 * {@code SRVDestroyed} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 5.16</a>
 */
public record SRVDestroyed(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "SRVDestroyed";

    public SRVDestroyed {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "SRVType")
                .ifPresent(value -> facts.add(
                        "SRV type "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("ID"))
                .ifPresent(value -> facts.add("vehicle ID " + value));
        String sentence = "The player's surface reconnaissance vehicle "
                + "(SRV) was destroyed"
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(
                sentence,
                "This event does not identify the cause of destruction."
        ));
    }
}
