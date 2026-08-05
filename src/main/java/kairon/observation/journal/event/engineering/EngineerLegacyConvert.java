package kairon.observation.journal.event.engineering;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;
import kairon.observation.journal.UnrecognisedEventVariant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code EngineerLegacyConvert} journal event.
 *
 * <p>One wire event, two domain events and a gap. {@code IsPreview}
 * distinguishes the game showing what a conversion <em>would</em> do from the
 * conversion itself, and nothing else in the request carries that distinction —
 * the semantic adapter does not emit the flag. The record used to answer it
 * with a ternary inside its own description, which made it the one place in the
 * system where a class meant two things and only the description knew.</p>
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.14</a>
 */
public sealed interface EngineerLegacyConvert
        extends LlmPresentableJournalEvent {

    String EVENT_TYPE = "EngineerLegacyConvert";

    /** What the conversion itself reports. */
    String CONVERTED_DESCRIPTION =
            "A legacy engineered module was converted to the current format.";

    /** The domain event this record actually is. */
    static EngineerLegacyConvert of(RawJournalData raw) {
        JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        return LlmPresentableJournalEvent
                .booleanValue(raw.parsedJsonObject().get("IsPreview"))
                .<EngineerLegacyConvert>map(preview -> preview
                        ? new Previewed(raw)
                        : new Converted(raw))
                .orElseGet(() -> new Unrecognised(raw));
    }

    /** The game showed what a conversion would do. */
    record Previewed(RawJournalData raw) implements EngineerLegacyConvert {

        public Previewed {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A conversion of a legacy engineered module was previewed.";
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return presentation(raw, "The player previewed conversion of a "
                    + "legacy engineered module to the current engineering "
                    + "format; this does not establish that conversion "
                    + "occurred.");
        }
    }

    /** The conversion itself. */
    record Converted(RawJournalData raw) implements EngineerLegacyConvert {

        public Converted {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return CONVERTED_DESCRIPTION;
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return presentation(raw, "The player converted a legacy "
                    + "engineered module to the current engineering format.");
        }
    }

    /**
     * The record carries no usable {@code IsPreview}.
     *
     * <p>It used to say what {@link Converted} says — an absent flag was read
     * as a conversion, first through an {@code orElse(false)} and then, once
     * the class was split, as a written-down constant. Both were a claim the
     * record does not make: a preview and a conversion are the two things this
     * record can be, and one without the flag is neither of them told apart.
     * Saying so is the one thing this variant is for.</p>
     */
    record Unrecognised(RawJournalData raw)
            implements EngineerLegacyConvert, UnrecognisedEventVariant {

        public Unrecognised {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A legacy engineered module conversion or preview was "
                    + "reported without saying which.";
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return presentation(raw, "The journal reported a legacy "
                    + "engineered module conversion or conversion preview, "
                    + "but did not identify which one.");
        }
    }

    // ----------------------------------------------------------- presentation

    private static LlmEventPresentation presentation(
            RawJournalData raw,
            String lead
    ) {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();
        sentences.add(lead);
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
