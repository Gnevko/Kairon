package kairon.observation.journal;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * Contract implemented by a journal event only after its official semantics
 * have been researched.
 *
 * <p>The sentence explains what the source reports; it must not assign
 * narrative importance, rarity, value, emotion, intent, or comment-worthiness.
 * Raw journal data remains the authoritative observation and trace evidence.
 * </p>
 */
public interface LlmPresentableJournalEvent
        extends JournalEventObservation {

    /**
     * What this event literally reports, in one short sentence.
     *
     * <p>The definition of the occurrence, not an account of this one. It is
     * the same sentence for every record of the type, and it carries no value
     * the record supplies: what happened is here, what it happened to stays in
     * the event's own named fields. It is the only thing the model is told the
     * event <em>is</em> — no internal kind, no wire name, no enum spelling
     * reaches it.</p>
     *
     * <p>It states no importance, rarity, value, danger, player intent,
     * supposed cause or next step, and it never assembles a sentence out of
     * body names, systems, coordinates or counts.</p>
     *
     * <p>It never chooses. A wire event whose records carry more than one
     * distinct model-facing assertion is dispatched to one class per assertion
     * by the parser, and each class answers with its own constant sentence — so
     * a description that read the record's own fields to pick a phrase would be
     * a second dispatch, made where nothing else can see it.</p>
     *
     * <p>It is the whole of what this interface says to the model. A second
     * method once rendered each record's own facts as prose —
     * {@code llmPresentation()}, researched per event and never called by
     * anything after ADR-0013 moved the model onto structured events. It was
     * removed on 2026-08-08 with its 121 implementations and the 132 tests that
     * were its only readers; the research it recorded lives on in the semantic
     * adapters, which read the same source fields and are read by the
     * runtime.</p>
     */
    String modelFacingDescription();

    /**
     * Returns the localized source label when available, then a non-opaque
     * source value. Internal {@code $symbol;} identifiers are not exposed as
     * if they were human-readable facts.
     */
    static Optional<String> displayText(
            JsonNode object,
            String fieldName
    ) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(fieldName, "fieldName");
        Optional<String> localized = textual(
                object.get(fieldName + "_Localised")
        ).filter(value -> !isOpaqueSymbol(value));
        if (localized.isPresent()) {
            return localized;
        }
        return textual(object.get(fieldName))
                .filter(value -> !isOpaqueSymbol(value));
    }

    static Optional<String> textual(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return Optional.empty();
        }
        String text = normalizeInlineText(value.textValue());
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /**
     * A journal flag, read either as a boolean or as the 0/1 some records use.
     *
     * <p>Kept because the parser dispatches on one: {@code IsPreview} decides
     * which of the two {@code EngineerLegacyConvert} events a record is.</p>
     */
    static Optional<Boolean> booleanValue(JsonNode value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value.isBoolean()) {
            return Optional.of(value.booleanValue());
        }
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            return switch (value.intValue()) {
                case 0 -> Optional.of(false);
                case 1 -> Optional.of(true);
                default -> Optional.empty();
            };
        }
        return Optional.empty();
    }

    /**
     * Five more helpers stood here \u2014 {@code decimal},
     * {@code nonNegativeIntegral}, {@code formattedInteger}, {@code joinFacts}
     * and {@code quoted}. Every one of them existed to phrase a number or a
     * name inside a rendered sentence, and they went with the sentences on
     * 2026-08-08. What is left is what the parser and the registry read: a
     * label that is safe to show, a flag the parser dispatches on, and the
     * whitespace normalisation under both.
     */
    static String normalizeInlineText(String value) {
        Objects.requireNonNull(value, "value");
        StringBuilder normalized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)
                    || Character.isWhitespace(character)) {
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.append(character);
        }
        return normalized.toString().strip();
    }

    private static boolean isOpaqueSymbol(String value) {
        return value.startsWith("$") && value.endsWith(";");
    }
}
