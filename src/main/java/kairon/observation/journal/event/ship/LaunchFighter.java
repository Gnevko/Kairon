package kairon.observation.journal.event.ship;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code LaunchFighter} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.26</a>
 */
public record LaunchFighter(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "LaunchFighter";

    public LaunchFighter {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "Loadout")
                .ifPresent(value -> facts.add(
                        "loadout "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("ID"))
                .ifPresent(value -> facts.add("fighter ID " + value));
        LlmPresentableJournalEvent
                .booleanValue(event.get("PlayerControlled"))
                .ifPresent(value -> facts.add(
                        value
                                ? "player-controlled from launch"
                                : "not player-controlled from launch"
                ));
        String sentence = "A ship-launched fighter was launched"
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
