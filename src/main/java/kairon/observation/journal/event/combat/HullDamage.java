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
 * {@code HullDamage} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 5.10</a>
 */
public record HullDamage(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "HullDamage";

    public HullDamage {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();
        sentences.add(
                "Hull health crossed one of the journal's 20-percentage-point "
                        + "damage thresholds."
        );
        LlmPresentableJournalEvent.decimal(event.get("Health"))
                .ifPresent(health -> sentences.add(
                        "The reported hull-health source value is "
                                + health
                                + "."
                ));

        List<String> vesselFacts = new ArrayList<>();
        LlmPresentableJournalEvent.booleanValue(event.get("Fighter"))
                .ifPresent(fighter -> vesselFacts.add(
                        fighter
                                ? "the damaged vessel is a ship-launched "
                                        + "fighter"
                                : "the damaged vessel is not a "
                                        + "ship-launched fighter"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("PlayerPilot"))
                .ifPresent(playerPilot -> vesselFacts.add(
                        playerPilot
                                ? "the player is piloting it"
                                : "the player is not piloting it"
                ));
        if (!vesselFacts.isEmpty()) {
            sentences.add(
                    "The journal reports that "
                            + LlmPresentableJournalEvent.joinFacts(vesselFacts)
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
