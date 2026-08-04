package kairon.observation.journal.event.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code CollectCargo} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 7.3</a>
 */
public record CollectCargo(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "CollectCargo";

    public CollectCargo {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "Cargo was scooped from space or a planet surface.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String cargo = LlmPresentableJournalEvent
                .displayText(event, "Type")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified cargo type");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.booleanValue(event.get("Stolen"))
                .ifPresent(stolen -> facts.add(
                        stolen
                                ? "the journal marks it as stolen"
                                : "the journal does not mark it as stolen"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MissionID"))
                .ifPresent(id -> facts.add("mission ID " + id));

        List<String> sentences = new ArrayList<>();
        sentences.add(
                "The player scooped cargo "
                        + cargo
                        + " from space or a planetary surface."
        );
        if (!facts.isEmpty()) {
            sentences.add(
                    "The cargo record includes "
                            + LlmPresentableJournalEvent.joinFacts(facts)
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
