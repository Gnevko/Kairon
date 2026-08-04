package kairon.llm;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;

/**
 * Applies conservative, provider-independent output-quality checks against
 * recently delivered comments.
 *
 * <p>This guard can reject an exact repeat or a strongly overlapping lexical
 * paraphrase. It does not interpret journal events, establish broad semantic
 * equivalence, or decide whether an event deserves a comment.</p>
 */
public final class CommentNoveltyGuard {

    public static final String EXACT_DUPLICATE =
            "DUPLICATE_PREVIOUS_COMMENT";
    public static final String NEAR_DUPLICATE =
            "NEAR_DUPLICATE_PREVIOUS_COMMENT";

    private static final int MINIMUM_NEAR_DUPLICATE_CODE_POINTS = 24;
    private static final double TRIGRAM_DICE_THRESHOLD = 0.82;
    private static final double WORD_JACCARD_THRESHOLD = 0.70;
    private static final double WORD_OVERLAP_THRESHOLD = 0.90;
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", UNICODE_CHARACTER_CLASS);
    private static final Pattern TRAILING_PUNCTUATION =
            Pattern.compile("\\p{P}+$", UNICODE_CHARACTER_CLASS);
    private static final Pattern NON_WORD =
            Pattern.compile("[^\\p{L}\\p{N}]+", UNICODE_CHARACTER_CLASS);

    public Optional<String> findViolation(
            String candidate,
            List<String> previousComments
    ) {
        String exactCandidate = normalizeExact(candidate);
        List<String> comments = List.copyOf(Objects.requireNonNull(
                previousComments,
                "previousComments"
        ));
        for (String previous : comments) {
            if (exactCandidate.equals(normalizeExact(previous))) {
                return Optional.of(EXACT_DUPLICATE);
            }
        }

        String nearCandidate = normalizeForSimilarity(candidate);
        if (nearCandidate.codePointCount(0, nearCandidate.length())
                < MINIMUM_NEAR_DUPLICATE_CODE_POINTS) {
            return Optional.empty();
        }
        for (String previous : comments) {
            String nearPrevious = normalizeForSimilarity(previous);
            if (nearPrevious.codePointCount(0, nearPrevious.length())
                    < MINIMUM_NEAR_DUPLICATE_CODE_POINTS) {
                continue;
            }
            if (isNearDuplicate(nearCandidate, nearPrevious)) {
                return Optional.of(NEAR_DUPLICATE);
            }
        }
        return Optional.empty();
    }

    private static boolean isNearDuplicate(
            String candidate,
            String previous
    ) {
        double trigramDice = trigramDice(candidate, previous);
        if (trigramDice < TRIGRAM_DICE_THRESHOLD) {
            return false;
        }
        Set<String> candidateWords = words(candidate);
        Set<String> previousWords = words(previous);
        int intersection = 0;
        for (String word : candidateWords) {
            if (previousWords.contains(word)) {
                intersection++;
            }
        }
        int union = candidateWords.size()
                + previousWords.size()
                - intersection;
        double jaccard = union == 0
                ? 1.0
                : (double) intersection / union;
        int smallerWordCount = Math.min(
                candidateWords.size(),
                previousWords.size()
        );
        double overlap = smallerWordCount == 0
                ? 1.0
                : (double) intersection / smallerWordCount;
        return jaccard >= WORD_JACCARD_THRESHOLD
                || overlap >= WORD_OVERLAP_THRESHOLD;
    }

    private static double trigramDice(String left, String right) {
        Map<Trigram, Integer> leftCounts = trigrams(left);
        Map<Trigram, Integer> rightCounts = trigrams(right);
        int leftTotal = leftCounts.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        int rightTotal = rightCounts.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        int shared = 0;
        for (Map.Entry<Trigram, Integer> entry : leftCounts.entrySet()) {
            shared += Math.min(
                    entry.getValue(),
                    rightCounts.getOrDefault(entry.getKey(), 0)
            );
        }
        return leftTotal + rightTotal == 0
                ? 1.0
                : (2.0 * shared) / (leftTotal + rightTotal);
    }

    private static Map<Trigram, Integer> trigrams(String value) {
        int[] codePoints = ("  " + value + "  ").codePoints().toArray();
        Map<Trigram, Integer> counts = new HashMap<>();
        for (int index = 0; index <= codePoints.length - 3; index++) {
            Trigram trigram = new Trigram(
                    codePoints[index],
                    codePoints[index + 1],
                    codePoints[index + 2]
            );
            counts.merge(trigram, 1, Integer::sum);
        }
        return counts;
    }

    private static Set<String> words(String value) {
        return new HashSet<>(List.of(value.split(" ")));
    }

    private static String normalizeExact(String text) {
        String normalized = Normalizer.normalize(
                Objects.requireNonNull(text, "comment"),
                Normalizer.Form.NFKC
        ).strip().toLowerCase(Locale.ROOT);
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
        return TRAILING_PUNCTUATION.matcher(normalized)
                .replaceAll("")
                .strip();
    }

    private static String normalizeForSimilarity(String text) {
        String normalized = Normalizer.normalize(
                Objects.requireNonNull(text, "comment"),
                Normalizer.Form.NFKC
        ).toLowerCase(Locale.ROOT)
                .replace('\u0451', '\u0435');
        normalized = NON_WORD.matcher(normalized).replaceAll(" ");
        return WHITESPACE.matcher(normalized).replaceAll(" ").strip();
    }

    private record Trigram(int first, int second, int third) {
    }
}
