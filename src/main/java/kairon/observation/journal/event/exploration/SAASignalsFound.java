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

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code SAASignalsFound} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.15</a>
 */
public record SAASignalsFound(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "SAASignalsFound";

    public SAASignalsFound {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();

        StringBuilder identity = new StringBuilder(
                "The Surface Area Analysis scanner reported signals"
        );
        displayText(event, "BodyName")
                .ifPresent(name -> identity
                        .append(" on target ")
                        .append(quoted(name)));
        integral(event.get("BodyID"))
                .ifPresent(bodyId -> identity
                        .append(", body ID ")
                        .append(bodyId));
        identity.append('.');
        sentences.add(identity.toString());

        signalCounts(event).ifPresent(sentences::add);
        genuses(event).ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> signalCounts(JsonNode event) {
        JsonNode signals = event.get("Signals");
        if (signals == null || !signals.isArray()) {
            return Optional.empty();
        }
        List<String> facts = new ArrayList<>();
        for (JsonNode signal : signals) {
            Optional<String> type = displayText(signal, "Type");
            Optional<Long> count = integral(signal.get("Count"));
            if (type.isPresent() && count.isPresent()) {
                facts.add(
                        quoted(type.orElseThrow())
                                + ": "
                                + count.orElseThrow()
                );
            }
        }
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The reported signal counts by type are "
                        + String.join(", ", facts)
                        + "."
        );
    }

    private static Optional<String> genuses(JsonNode event) {
        JsonNode genuses = event.get("Genuses");
        if (genuses == null || !genuses.isArray()) {
            return Optional.empty();
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode genus : genuses) {
            displayText(genus, "Genus")
                    .map(LlmPresentableJournalEvent::quoted)
                    .ifPresent(labels::add);
        }
        if (labels.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The journal lists these biological genera for the target: "
                        + String.join(", ", labels)
                        + "."
        );
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
