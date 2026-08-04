package kairon.observation.journal.event.mission;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code MissionRedirected} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.24</a>
 */
public record MissionRedirected(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "MissionRedirected";

    public MissionRedirected {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A mission was updated with a new destination.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String mission = LlmPresentableJournalEvent
                .displayText(event, "Name")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("a mission");
        List<String> oldDestination = destination(event, "Old");
        List<String> newDestination = destination(event, "New");
        List<String> sentences = new ArrayList<>();
        sentences.add(
                "The destination for "
                        + mission
                        + " was redirected."
        );
        if (!oldDestination.isEmpty() || !newDestination.isEmpty()) {
            String oldText = oldDestination.isEmpty()
                    ? "an unreported previous destination"
                    : LlmPresentableJournalEvent.joinFacts(oldDestination);
            String newText = newDestination.isEmpty()
                    ? "an unreported new destination"
                    : LlmPresentableJournalEvent.joinFacts(newDestination);
            sentences.add(
                    "The recorded destination changed from "
                            + oldText
                            + " to "
                            + newText
                            + "."
            );
        }
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MissionID"))
                .ifPresent(id -> sentences.add("The mission ID is " + id + "."));
        return new LlmEventPresentation(sentences);
    }

    private static List<String> destination(
            JsonNode event,
            String prefix
    ) {
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent
                .textual(event.get(prefix + "DestinationSystem"))
                .ifPresent(value -> facts.add(
                        "system " + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .textual(event.get(prefix + "DestinationStation"))
                .ifPresent(value -> facts.add(
                        "station " + LlmPresentableJournalEvent.quoted(value)
                ));
        return facts;
    }
}
