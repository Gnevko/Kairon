package kairon.observation.journal;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;

/**
 * Contract implemented by a journal event only after its official semantics
 * have been researched and encoded as deterministic English sentences.
 *
 * <p>The presentation explains source facts; it must not assign narrative
 * importance, rarity, value, emotion, intent, or comment-worthiness. Raw
 * journal data remains the authoritative observation and trace evidence.</p>
 */
public interface LlmPresentableJournalEvent
        extends JournalEventObservation {

    LlmEventPresentation llmPresentation();

    /**
     * Immutable, human-readable facts contributed by one source event.
     */
    record LlmEventPresentation(List<String> sentences) {

        private static final Pattern SENTENCE_END =
                Pattern.compile("[.!?\\u2026]$", UNICODE_CHARACTER_CLASS);

        public LlmEventPresentation {
            sentences = List.copyOf(Objects.requireNonNull(
                    sentences,
                    "sentences"
            ));
            if (sentences.isEmpty()) {
                throw new IllegalArgumentException(
                        "sentences must not be empty"
                );
            }
            for (String sentence : sentences) {
                Objects.requireNonNull(sentence, "sentence");
                if (sentence.isBlank()) {
                    throw new IllegalArgumentException(
                            "presentation sentences must not be blank"
                    );
                }
                if (!sentence.equals(sentence.strip())) {
                    throw new IllegalArgumentException(
                            "presentation sentences must be stripped"
                    );
                }
                if (sentence.codePoints().anyMatch(
                        LlmEventPresentation::isLineSeparator
                )) {
                    throw new IllegalArgumentException(
                            "presentation sentences must be single-line"
                    );
                }
                if (!SENTENCE_END.matcher(sentence).find()) {
                    throw new IllegalArgumentException(
                            "presentation sentences must end with punctuation"
                    );
                }
            }
        }

        public String text() {
            return String.join(" ", sentences);
        }

        private static boolean isLineSeparator(int codePoint) {
            return codePoint == '\r'
                    || codePoint == '\n'
                    || codePoint == 0x0085
                    || codePoint == 0x2028
                    || codePoint == 0x2029;
        }
    }

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

    static Optional<String> decimal(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return Optional.empty();
        }
        BigDecimal decimal = value.decimalValue().stripTrailingZeros();
        return Optional.of(decimal.toPlainString());
    }

    static Optional<Long> nonNegativeIntegral(JsonNode value) {
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToLong()
                && value.longValue() >= 0
                ? Optional.of(value.longValue())
                : Optional.empty();
    }

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

    static String formattedInteger(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    static String joinFacts(List<String> facts) {
        List<String> immutableFacts = List.copyOf(
                Objects.requireNonNull(facts, "facts")
        );
        if (immutableFacts.isEmpty()) {
            throw new IllegalArgumentException("facts must not be empty");
        }
        if (immutableFacts.size() == 1) {
            return immutableFacts.getFirst();
        }
        if (immutableFacts.size() == 2) {
            return immutableFacts.getFirst()
                    + " and "
                    + immutableFacts.getLast();
        }
        return String.join(
                ", ",
                immutableFacts.subList(0, immutableFacts.size() - 1)
        ) + ", and " + immutableFacts.getLast();
    }

    static String quoted(String value) {
        String normalized = normalizeInlineText(
                Objects.requireNonNull(value, "value")
        );
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "quoted value must not be blank"
            );
        }
        return "\u201c"
                + normalized
                        .replace('\u201c', '"')
                        .replace('\u201d', '"')
                + "\u201d";
    }

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
