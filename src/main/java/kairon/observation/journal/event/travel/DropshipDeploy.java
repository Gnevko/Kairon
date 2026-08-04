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
 * {@code DropshipDeploy} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 12.16</a>
 */
public record DropshipDeploy(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "DropshipDeploy";

    public DropshipDeploy {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("StarSystem"))
                .ifPresent(value -> facts.add(
                        "system "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.textual(event.get("Body"))
                .ifPresent(value -> facts.add(
                        "body "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("BodyID"))
                .ifPresent(value -> facts.add("body ID " + value));
        LlmPresentableJournalEvent.booleanValue(event.get("OnStation"))
                .ifPresent(value -> facts.add(
                        value ? "on a station" : "not on a station"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("OnPlanet"))
                .ifPresent(value -> facts.add(
                        value ? "on a planet" : "not on a planet"
                ));
        String sentence = "The player exited a shuttle dropship at a "
                + "conflict zone"
                + (facts.isEmpty()
                ? "."
                : ", with the deployment recorded in "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
