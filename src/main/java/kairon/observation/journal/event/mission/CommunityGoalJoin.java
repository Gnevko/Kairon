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
 * {@code CommunityGoalJoin} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.6</a>
 */
public record CommunityGoalJoin(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "CommunityGoalJoin";

    public CommunityGoalJoin {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "The Commander signed up for a community goal.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String goal = LlmPresentableJournalEvent
                .displayText(event, "Name")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unnamed community goal");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("CGID"))
                .ifPresent(id -> facts.add("community-goal ID " + id));
        LlmPresentableJournalEvent.textual(event.get("System"))
                .ifPresent(system -> facts.add(
                        "star system "
                                + LlmPresentableJournalEvent.quoted(system)
                ));
        StringBuilder sentence = new StringBuilder(
                "The player signed up for "
        ).append(goal);
        if (!facts.isEmpty()) {
            sentence.append(", with ")
                    .append(LlmPresentableJournalEvent.joinFacts(facts));
        }
        sentence.append('.');
        return new LlmEventPresentation(List.of(sentence.toString()));
    }
}
