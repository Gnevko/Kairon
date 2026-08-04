package kairon.observation.journal.event.exploration;

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
 * {@code FSSBodySignals} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.5</a>
 */
public record FSSBodySignals(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "FSSBodySignals";

    public FSSBodySignals {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A full spectrum system scan reported signal data for a body.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> identityFacts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "BodyName")
                .ifPresent(value -> identityFacts.add(
                        "body "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("BodyID"))
                .ifPresent(value -> identityFacts.add(
                        "body ID " + value
                ));

        StringBuilder identity = new StringBuilder(
                "While completing a Full Spectrum System Scan, the journal "
                        + "reported Surface Area Analysis signal counts"
        );
        if (!identityFacts.isEmpty()) {
            identity.append(" for ")
                    .append(LlmPresentableJournalEvent.joinFacts(
                            identityFacts
                    ));
        }
        identity.append('.');

        List<String> sentences = new ArrayList<>();
        sentences.add(identity.toString());
        signalCounts(event).ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> signalCounts(JsonNode event) {
        JsonNode signals = event.get("Signals");
        if (signals == null || !signals.isArray()) {
            return Optional.empty();
        }
        List<String> facts = new ArrayList<>();
        for (JsonNode signal : signals) {
            Optional<String> type =
                    LlmPresentableJournalEvent.displayText(signal, "Type");
            Optional<Long> count = LlmPresentableJournalEvent
                    .nonNegativeIntegral(signal.get("Count"));
            if (type.isPresent() && count.isPresent()) {
                facts.add(
                        LlmPresentableJournalEvent.quoted(type.orElseThrow())
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
}
