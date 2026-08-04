package kairon.observation.journal.event.travel;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code SupercruiseEntry} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 4.14</a>
 */
public record SupercruiseEntry(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "SupercruiseEntry";

    public SupercruiseEntry {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A ship entered supercruise from normal space.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String system = LlmPresentableJournalEvent
                .textual(event.get("StarSystem"))
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified star system");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.booleanValue(event.get("Taxi"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> facts.add(
                        "the player was travelling in a taxi"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Multicrew"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> facts.add(
                        "the player was aboard another player's vessel"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Wanted"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> facts.add(
                        "the player was wanted in this system"
                ));
        String sentence = "The player's vessel entered supercruise from "
                + "normal space in system "
                + system
                + (facts.isEmpty()
                ? "."
                : "; "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
