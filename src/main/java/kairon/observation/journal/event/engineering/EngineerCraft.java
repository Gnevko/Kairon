package kairon.observation.journal.event.engineering;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code EngineerCraft} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.13</a>
 */
public record EngineerCraft(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "EngineerCraft";

    public EngineerCraft {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();

        StringBuilder request = new StringBuilder(
                "The player requested an engineer upgrade"
        );
        LlmPresentableJournalEvent.textual(event.get("BlueprintName"))
                .ifPresent(blueprint -> request
                        .append(" using blueprint ")
                        .append(LlmPresentableJournalEvent.quoted(blueprint)));
        LlmPresentableJournalEvent.textual(event.get("Engineer"))
                .ifPresent(engineer -> request
                        .append(" from engineer ")
                        .append(LlmPresentableJournalEvent.quoted(engineer)));
        request.append('.');
        sentences.add(request.toString());

        moduleAndBlueprint(event).ifPresent(sentences::add);
        namedCounts(event.get("Ingredients")).ifPresent(ingredients ->
                sentences.add("Ingredients consumed: " + ingredients + ".")
        );
        modifierDetails(event.get("Modifiers")).ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> moduleAndBlueprint(JsonNode event) {
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "Module")
                .ifPresent(module -> facts.add(
                        "module " + LlmPresentableJournalEvent.quoted(module)
                ));
        LlmPresentableJournalEvent.textual(event.get("Slot"))
                .ifPresent(slot -> facts.add(
                        "slot " + LlmPresentableJournalEvent.quoted(slot)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("Level"))
                .ifPresent(level -> facts.add("blueprint level " + level));
        LlmPresentableJournalEvent.decimal(event.get("Quality"))
                .filter(EngineerCraft::isZeroToOne)
                .ifPresent(quality -> facts.add(
                        "blueprint refinement progress "
                                + quality
                                + " on the documented 0-to-1 scale"
                ));
        LlmPresentableJournalEvent.displayText(
                        event,
                        "ApplyExperimentalEffect"
                )
                .ifPresent(effect -> facts.add(
                        "experimental effect "
                                + LlmPresentableJournalEvent.quoted(effect)
                ));
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The upgrade record reports "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + "."
        );
    }

    private static Optional<String> namedCounts(JsonNode values) {
        if (values == null || !values.isArray()) {
            return Optional.empty();
        }
        List<String> entries = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isObject()) {
                continue;
            }
            Optional<String> name =
                    LlmPresentableJournalEvent.displayText(value, "Name");
            Optional<Long> count =
                    LlmPresentableJournalEvent.nonNegativeIntegral(
                            value.get("Count")
                    );
            if (name.isPresent() && count.isPresent()) {
                entries.add(
                        LlmPresentableJournalEvent.quoted(name.get())
                                + " x "
                                + LlmPresentableJournalEvent.formattedInteger(
                                        count.get()
                                )
                );
            }
        }
        return entries.isEmpty()
                ? Optional.empty()
                : Optional.of(String.join("; ", entries));
    }

    private static Optional<String> modifierDetails(JsonNode modifiers) {
        if (modifiers == null || !modifiers.isArray()) {
            return Optional.empty();
        }
        List<String> entries = new ArrayList<>();
        for (JsonNode modifier : modifiers) {
            if (!modifier.isObject()) {
                continue;
            }
            Optional<String> label =
                    LlmPresentableJournalEvent.displayText(
                            modifier,
                            "Label"
                    );
            if (label.isEmpty()) {
                continue;
            }
            List<String> facts = new ArrayList<>();
            LlmPresentableJournalEvent.decimal(modifier.get("Value"))
                    .ifPresent(value -> facts.add("new value " + value));
            LlmPresentableJournalEvent.textual(modifier.get("ValueStr"))
                    .ifPresent(value -> facts.add(
                            "new text value "
                                    + LlmPresentableJournalEvent.quoted(value)
                    ));
            LlmPresentableJournalEvent
                    .decimal(modifier.get("OriginalValue"))
                    .ifPresent(value -> facts.add(
                            "original value " + value
                    ));
            LlmPresentableJournalEvent
                    .booleanValue(modifier.get("LessIsGood"))
                    .ifPresent(lessIsGood -> facts.add(
                            lessIsGood
                                    ? "lower values are beneficial"
                                    : "higher values are beneficial"
                    ));
            if (!facts.isEmpty()) {
                entries.add(
                        LlmPresentableJournalEvent.quoted(label.get())
                                + ": "
                                + LlmPresentableJournalEvent.joinFacts(facts)
                );
            }
        }
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "Documented modifier results: "
                        + String.join("; ", entries)
                        + "."
        );
    }

    private static boolean isZeroToOne(String value) {
        BigDecimal number = new BigDecimal(value);
        return number.signum() >= 0
                && number.compareTo(BigDecimal.ONE) <= 0;
    }
}
