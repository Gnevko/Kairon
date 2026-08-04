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
 * {@code USSDrop} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.46</a>
 */
public record USSDrop(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "USSDrop";

    public USSDrop {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "USSType")
                .ifPresent(value -> facts.add(
                        "signal description "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("USSThreat"))
                .ifPresent(value -> facts.add(
                        "threat level " + value
                ));

        List<String> sentences = new ArrayList<>();
        sentences.add(
                "The vessel dropped from supercruise at an unidentified "
                        + "signal source."
        );
        if (!facts.isEmpty()) {
            sentences.add(
                    "The journal reports "
                            + LlmPresentableJournalEvent.joinFacts(facts)
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
