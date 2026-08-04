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
 * {@code PVPKill} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 5.13</a>
 */
public record PVPKill(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "PVPKill";

    public PVPKill {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "The Commander killed another player.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder outcome = new StringBuilder(
                "The player killed another player"
        );
        LlmPresentableJournalEvent.displayText(event, "Victim")
                .ifPresent(victim -> outcome
                        .append(", ")
                        .append(LlmPresentableJournalEvent.quoted(victim)));
        outcome.append('.');

        List<String> sentences = new ArrayList<>();
        sentences.add(outcome.toString());
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("CombatRank"))
                .filter(rank -> rank <= 8)
                .ifPresent(rank -> sentences.add(
                        "The victim's combat-rank value is "
                                + rank
                                + " on the documented 0-to-8 scale."
                ));
        return new LlmEventPresentation(sentences);
    }
}
