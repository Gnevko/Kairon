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
 * {@code SAAScanComplete} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.14</a>
 */
public record SAAScanComplete(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "SAAScanComplete";

    public SAAScanComplete {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();

        StringBuilder completion = new StringBuilder(
                "The journal recorded completion of Surface Area Analysis"
        );
        displayText(event, "BodyName")
                .ifPresent(name -> completion
                        .append(" for ")
                        .append(quoted(name)));
        integral(event.get("BodyID"))
                .ifPresent(bodyId -> completion
                        .append(" (body ID ")
                        .append(bodyId)
                        .append(')'));
        completion.append('.');
        sentences.add(completion.toString());

        Optional<Long> probesUsed = integral(event.get("ProbesUsed"));
        Optional<Long> efficiencyTarget =
                integral(event.get("EfficiencyTarget"));
        if (probesUsed.isPresent() || efficiencyTarget.isPresent()) {
            StringBuilder probes = new StringBuilder("The journal reports");
            probesUsed.ifPresent(value -> probes
                    .append(' ')
                    .append(value)
                    .append(" probes used"));
            if (probesUsed.isPresent() && efficiencyTarget.isPresent()) {
                probes.append(" and");
            }
            efficiencyTarget.ifPresent(value -> probes
                    .append(" an efficiency target of ")
                    .append(value)
                    .append(" probes"));
            probes.append('.');
            sentences.add(probes.toString());
        }

        names(event.get("Discoverers"))
                .ifPresent(names -> sentences.add(
                        "The journal lists these discoverers: "
                                + names
                                + "."
                ));
        names(event.get("Mappers"))
                .ifPresent(names -> sentences.add(
                        "The journal lists these mappers: "
                                + names
                                + "."
                ));
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> names(JsonNode values) {
        if (values == null || !values.isArray()) {
            return Optional.empty();
        }
        List<String> names = new ArrayList<>();
        for (JsonNode value : values) {
            textual(value)
                    .map(LlmPresentableJournalEvent::quoted)
                    .ifPresent(names::add);
        }
        return names.isEmpty()
                ? Optional.empty()
                : Optional.of(String.join(", ", names));
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
