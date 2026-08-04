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
 * {@code Interdicted} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 5.11</a>
 */
public record Interdicted(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Interdicted";

    public Interdicted {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "The Commander was interdicted by another pilot or an NPC.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder eventFact = new StringBuilder(
                "The player was interdicted"
        );
        LlmPresentableJournalEvent.displayText(event, "Interdictor")
                .ifPresent(name -> eventFact
                        .append(" by ")
                        .append(LlmPresentableJournalEvent.quoted(name)));
        eventFact.append('.');

        List<String> sentences = new ArrayList<>();
        sentences.add(eventFact.toString());
        LlmPresentableJournalEvent.booleanValue(event.get("Submitted"))
                .ifPresent(submitted -> sentences.add(
                        submitted
                                ? "The player submitted to the interdiction."
                                : "The player did not submit to the "
                                        + "interdiction."
                ));

        List<String> identity = new ArrayList<>();
        LlmPresentableJournalEvent.booleanValue(event.get("IsPlayer"))
                .ifPresent(isPlayer -> identity.add(
                        isPlayer ? "another player" : "an NPC"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("IsThargoid"))
                .ifPresent(isThargoid -> identity.add(
                        isThargoid ? "a Thargoid" : "not a Thargoid"
                ));
        LlmPresentableJournalEvent.textual(event.get("Faction"))
                .ifPresent(faction -> identity.add(
                        "faction "
                                + LlmPresentableJournalEvent.quoted(faction)
                ));
        LlmPresentableJournalEvent.textual(event.get("Power"))
                .ifPresent(power -> identity.add(
                        "Power "
                                + LlmPresentableJournalEvent.quoted(power)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("CombatRank"))
                .filter(rank -> rank <= 8)
                .ifPresent(rank -> identity.add(
                        "combat-rank value " + rank + " on the 0-to-8 scale"
                ));
        if (!identity.isEmpty()) {
            sentences.add(
                    "The journal identifies the interdictor as "
                            + LlmPresentableJournalEvent.joinFacts(identity)
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
