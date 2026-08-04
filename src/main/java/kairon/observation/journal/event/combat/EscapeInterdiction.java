package kairon.observation.journal.event.combat;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code EscapeInterdiction} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 5.5</a>
 */
public record EscapeInterdiction(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "EscapeInterdiction";

    public EscapeInterdiction {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder outcome = new StringBuilder(
                "The player escaped an interdiction attempt"
        );
        LlmPresentableJournalEvent.displayText(event, "Interdictor")
                .ifPresent(name -> outcome
                        .append(" by ")
                        .append(LlmPresentableJournalEvent.quoted(name)));
        outcome.append('.');

        List<String> sentences = new ArrayList<>();
        sentences.add(outcome.toString());
        List<String> identityFacts = new ArrayList<>();
        LlmPresentableJournalEvent.booleanValue(event.get("IsPlayer"))
                .ifPresent(isPlayer -> identityFacts.add(
                        isPlayer
                                ? "another player"
                                : "an NPC"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("IsThargoid"))
                .ifPresent(isThargoid -> identityFacts.add(
                        isThargoid ? "a Thargoid" : "not a Thargoid"
                ));
        if (!identityFacts.isEmpty()) {
            sentences.add(
                    "The journal identifies the interdictor as "
                            + LlmPresentableJournalEvent.joinFacts(
                                    identityFacts
                            )
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
