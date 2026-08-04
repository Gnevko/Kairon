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
 * {@code MissionAbandoned} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.20</a>
 */
public record MissionAbandoned(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "MissionAbandoned";

    public MissionAbandoned {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A mission was abandoned.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String mission = LlmPresentableJournalEvent
                .displayText(event, "Name")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unnamed mission");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("MissionID"))
                .ifPresent(id -> facts.add("mission ID " + id));
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("Fine"))
                .ifPresent(fine -> facts.add(
                        "a fine of "
                                + LlmPresentableJournalEvent
                                .formattedInteger(fine)
                                + " credits"
                ));
        StringBuilder sentence = new StringBuilder(
                "The player abandoned "
        ).append(mission);
        if (!facts.isEmpty()) {
            sentence.append(", with ")
                    .append(LlmPresentableJournalEvent.joinFacts(facts));
        }
        sentence.append('.');
        return new LlmEventPresentation(List.of(sentence.toString()));
    }
}
