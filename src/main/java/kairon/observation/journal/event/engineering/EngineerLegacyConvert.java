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
 * {@code EngineerLegacyConvert} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.14</a>
 */
public record EngineerLegacyConvert(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "EngineerLegacyConvert";

    public EngineerLegacyConvert {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();
        LlmPresentableJournalEvent.booleanValue(event.get("IsPreview"))
                .ifPresentOrElse(
                        preview -> sentences.add(preview
                                ? "The player previewed conversion of a "
                                        + "legacy engineered module to the "
                                        + "current engineering format; this "
                                        + "does not establish that conversion "
                                        + "occurred."
                                : "The player converted a legacy engineered "
                                        + "module to the current engineering "
                                        + "format."),
                        () -> sentences.add(
                                "The journal reported a legacy engineered "
                                        + "module conversion or conversion "
                                        + "preview, but did not identify which "
                                        + "one."
                        )
                );

        conversionDetails(event).ifPresent(sentences::add);
        modifierDetails(event.get("Modifiers")).ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> conversionDetails(JsonNode event) {
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "Module")
                .ifPresent(module -> facts.add(
                        "module " + LlmPresentableJournalEvent.quoted(module)
                ));
        LlmPresentableJournalEvent.textual(event.get("Slot"))
                .ifPresent(slot -> facts.add(
                        "slot " + LlmPresentableJournalEvent.quoted(slot)
                ));
        LlmPresentableJournalEvent.textual(event.get("Engineer"))
                .ifPresent(engineer -> facts.add(
                        "engineer "
                                + LlmPresentableJournalEvent.quoted(engineer)
                ));
        LlmPresentableJournalEvent.textual(event.get("BlueprintName"))
                .ifPresent(blueprint -> facts.add(
                        "blueprint "
                                + LlmPresentableJournalEvent.quoted(blueprint)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("Level"))
                .ifPresent(level -> facts.add("resulting recipe level " + level));
        LlmPresentableJournalEvent.decimal(event.get("Quality"))
                .filter(EngineerLegacyConvert::isZeroToOne)
                .ifPresent(quality -> facts.add(
                        "blueprint refinement progress "
                                + quality
                                + " on the documented 0-to-1 scale"
                ));
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The conversion record reports "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + "."
        );
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
                "Documented conversion modifier results: "
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
