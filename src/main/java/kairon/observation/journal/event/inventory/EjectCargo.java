package kairon.observation.journal.event.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code EjectCargo} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 7.4</a>
 */
public record EjectCargo(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "EjectCargo";

    public EjectCargo {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String cargo = LlmPresentableJournalEvent
                .displayText(event, "Type")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified cargo type");
        String quantity = LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("Count"))
                .map(value -> LlmPresentableJournalEvent
                        .formattedInteger(value)
                        + " unit"
                        + (value == 1 ? "" : "s")
                        + " of ")
                .orElse("");
        List<String> sentences = new ArrayList<>();
        sentences.add(
                "The player ejected "
                        + quantity
                        + "cargo "
                        + cargo
                        + "."
        );

        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.booleanValue(event.get("Abandoned"))
                .ifPresent(abandoned -> facts.add(
                        abandoned
                                ? "the cargo was marked as abandoned"
                                : "the cargo was not marked as abandoned"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MissionID"))
                .ifPresent(id -> facts.add("mission ID " + id));
        LlmPresentableJournalEvent.textual(event.get("PowerplayOrigin"))
                .ifPresent(origin -> facts.add(
                        "Powerplay origin system "
                                + LlmPresentableJournalEvent.quoted(origin)
                ));
        if (!facts.isEmpty()) {
            sentences.add(
                    "The ejection record reports "
                            + LlmPresentableJournalEvent.joinFacts(facts)
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
