package kairon.observation.journal.event.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static kairon.observation.journal.LlmPresentableJournalEvent.displayText;
import static kairon.observation.journal.LlmPresentableJournalEvent.quoted;
import static kairon.observation.journal.LlmPresentableJournalEvent.textual;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code ScanOrganic} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 12.22</a>
 */
public record ScanOrganic(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ScanOrganic";

    public ScanOrganic {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "The organic sampling tool was used on an organic discovery.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String subject = organicSubject(event);
        String stage = textual(event.get("ScanType")).orElse("");

        String sentence = switch (stage) {
            case "Log" ->
                    "The Organic Sampling Tool logged the first scan in a "
                            + "sampling sequence for "
                            + subject
                            + "; the sequence is not yet complete.";
            case "Sample" ->
                    "The Organic Sampling Tool recorded a subsequent sample "
                            + "for "
                            + subject
                            + "; the sequence is not yet complete.";
            case "Analyse" ->
                    "The Organic Sampling Tool recorded the final scan and "
                            + "completed the sampling sequence for "
                            + subject
                            + ".";
            default -> stage.isEmpty()
                    ? "The journal recorded use of the Organic Sampling Tool "
                            + "for "
                            + subject
                            + "."
                    : "The journal recorded Organic Sampling Tool stage "
                            + quoted(stage)
                            + " for "
                            + subject
                            + ".";
        };
        return new LlmEventPresentation(List.of(sentence));
    }

    private static String organicSubject(JsonNode event) {
        List<String> labels = new ArrayList<>();
        displayText(event, "Genus")
                .ifPresent(value -> labels.add(
                        "genus " + quoted(value)
                ));
        displayText(event, "Species")
                .ifPresent(value -> labels.add(
                        "species " + quoted(value)
                ));
        displayText(event, "Variant")
                .ifPresent(value -> labels.add(
                        "variant " + quoted(value)
                ));

        StringBuilder subject = new StringBuilder(
                labels.isEmpty()
                        ? "an organic discovery"
                        : String.join(", ", labels)
        );
        integral(event.get("Body"))
                .ifPresent(bodyId -> subject
                        .append(" on body ID ")
                        .append(bodyId));
        return subject.toString();
    }

    private static Optional<Long> integral(JsonNode value) {
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToLong()
                && value.longValue() >= 0
                ? Optional.of(value.longValue())
                : Optional.empty();
    }
}
