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
 * {@code FuelScoop} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.19</a>
 */
public record FuelScoop(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "FuelScoop";

    public FuelScoop {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "Fuel was scooped from a star.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.decimal(event.get("Scooped"))
                .ifPresent(value -> facts.add(
                        value + " tonnes scooped in this update"
                ));
        LlmPresentableJournalEvent.decimal(event.get("Total"))
                .ifPresent(value -> facts.add(
                        "total ship fuel " + value + " tonnes"
                ));

        List<String> sentences = new ArrayList<>();
        sentences.add(
                "The journal recorded one fuel-scooping update"
                        + (facts.isEmpty()
                        ? "."
                        : ": "
                                + LlmPresentableJournalEvent.joinFacts(facts)
                                + ".")
        );
        sentences.add(
                "This record does not state that the complete fuel-scooping "
                        + "session has finished."
        );
        return new LlmEventPresentation(sentences);
    }
}
