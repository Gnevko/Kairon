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
 * {@code Interdiction} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 5.12</a>
 */
public record Interdiction(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Interdiction";

    public Interdiction {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder attempt = new StringBuilder(
                "The player attempted to interdict another pilot"
        );
        LlmPresentableJournalEvent.displayText(event, "Interdicted")
                .ifPresent(name -> attempt
                        .append(' ')
                        .append(LlmPresentableJournalEvent.quoted(name)));
        attempt.append('.');

        List<String> sentences = new ArrayList<>();
        sentences.add(attempt.toString());
        LlmPresentableJournalEvent.booleanValue(event.get("Success"))
                .ifPresent(success -> sentences.add(
                        success
                                ? "The interdiction succeeded."
                                : "The interdiction failed."
                ));

        List<String> targetFacts = new ArrayList<>();
        LlmPresentableJournalEvent.booleanValue(event.get("IsPlayer"))
                .ifPresent(isPlayer -> targetFacts.add(
                        isPlayer ? "another player" : "an NPC"
                ));
        LlmPresentableJournalEvent.textual(event.get("Faction"))
                .ifPresent(faction -> targetFacts.add(
                        "faction "
                                + LlmPresentableJournalEvent.quoted(faction)
                ));
        LlmPresentableJournalEvent.textual(event.get("Power"))
                .ifPresent(power -> targetFacts.add(
                        "Power "
                                + LlmPresentableJournalEvent.quoted(power)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("CombatRank"))
                .filter(rank -> rank <= 8)
                .ifPresent(rank -> targetFacts.add(
                        "combat-rank value " + rank + " on the 0-to-8 scale"
                ));
        if (!targetFacts.isEmpty()) {
            sentences.add(
                    "The target is identified as "
                            + LlmPresentableJournalEvent.joinFacts(targetFacts)
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
