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
 * {@code ReceiveText} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.36</a>
 */
public record ReceiveText(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ReceiveText";

    public ReceiveText {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String sender = LlmPresentableJournalEvent
                .displayText(event, "From")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unidentified sender");
        String message = LlmPresentableJournalEvent
                .displayText(event, "Message")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unrendered message");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("Channel"))
                .ifPresent(value -> facts.add(
                        "journal channel "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        String sentence = "A text message was received from "
                + sender
                + ": "
                + message
                + (facts.isEmpty()
                ? "."
                : "; "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
