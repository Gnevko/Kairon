package kairon.observation.journal.event.travel;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code FSDTarget} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 4.9</a>
 */
public record FSDTarget(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "FSDTarget";

    public FSDTarget {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A star system was selected to jump to.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        Optional<String> destination =
                LlmPresentableJournalEvent.textual(event.get("Name"))
                        .or(() -> LlmPresentableJournalEvent
                                .textual(event.get("StarSystem")));

        StringBuilder selection = new StringBuilder(
                "A star system was selected as the frame-shift-drive "
                        + "jump target"
        );
        destination.ifPresent(value -> selection
                .append(": ")
                .append(LlmPresentableJournalEvent.quoted(value)));
        selection.append('.');

        List<String> routeFacts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "StarClass")
                .ifPresent(value -> routeFacts.add(
                        "target star class "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("RemainingJumpsInRoute"))
                .ifPresent(value -> routeFacts.add(
                        "remaining jumps in the plotted route " + value
                ));

        List<String> sentences = new ArrayList<>();
        sentences.add(selection.toString());
        sentences.add(
                "This records target selection, not a completed hyperspace "
                        + "jump."
        );
        if (!routeFacts.isEmpty()) {
            sentences.add(
                    "The selected route reports "
                            + LlmPresentableJournalEvent.joinFacts(routeFacts)
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
