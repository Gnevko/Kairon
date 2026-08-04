package kairon.behavior.snapshot;

import kairon.behavior.model.ContextKey;
import kairon.behavior.model.PredictionBasis;
import kairon.behavior.normalize.NormalizedEventType;

import java.util.Objects;

/**
 * Compact immutable likely-next transition calculated by the graph model.
 *
 * <p>Carries the full prediction semantics the domain establishes, so a
 * downstream consumer never has to reconstruct any of it. Five distinct
 * measures are kept apart on purpose, because conflating any two of them
 * misstates the evidence:</p>
 *
 * <ul>
 *   <li>{@code probability} — a normalised share of decayed evidence weight
 *       over the outgoing edges of the cursor type. Sums to one across all
 *       available predictions;</li>
 *   <li>{@code basis} — whether that share came from the context bucket or from
 *       the global fallback. A single decayed observation in the bucket flips
 *       it to {@code CONTEXTUAL};</li>
 *   <li>{@code globalProbability} — the same share ignoring context entirely;</li>
 *   <li>{@code observedTransitionCount} — the factual all-time count of this
 *       transition across every context. There is no separate global count
 *       field because this <em>is</em> the global count;</li>
 *   <li>{@code contextObservedTransitionCount} — the factual all-time count of
 *       this transition inside {@code contextKey} alone.</li>
 * </ul>
 *
 * <p>{@code contextSupport} is <strong>cursor-level, not per-prediction</strong>:
 * it is the summed decayed context weight over all outgoing edges, and every
 * prediction from one calculation carries the same value. {@code effectiveWeight}
 * is a half-life-decayed weight plus a prior — diagnostic, and emphatically not
 * a confidence. The domain model defines no confidence and no qualitative
 * support band, so neither exists here.</p>
 */
public record SituationNextEventPrediction(
        NormalizedEventType sourceEventType,
        NormalizedEventType predictedEventType,
        double probability,
        PredictionBasis basis,
        double globalProbability,
        long observedTransitionCount,
        long contextObservedTransitionCount,
        double contextSupport,
        ContextKey contextKey,
        double effectiveWeight
) {

    public SituationNextEventPrediction {
        sourceEventType = Objects.requireNonNull(
                sourceEventType,
                "sourceEventType"
        );
        predictedEventType = Objects.requireNonNull(
                predictedEventType,
                "predictedEventType"
        );
        basis = Objects.requireNonNull(basis, "basis");
        contextKey = Objects.requireNonNull(contextKey, "contextKey");
        requireProbability(probability, "probability");
        requireProbability(globalProbability, "globalProbability");
        if (!Double.isFinite(effectiveWeight) || effectiveWeight < 0.0) {
            throw new IllegalArgumentException(
                    "effectiveWeight must be finite and nonnegative"
            );
        }
        if (!Double.isFinite(contextSupport) || contextSupport < 0.0) {
            throw new IllegalArgumentException(
                    "contextSupport must be finite and nonnegative"
            );
        }
        if (observedTransitionCount < 1) {
            throw new IllegalArgumentException(
                    "observedTransitionCount must be positive"
            );
        }
        if (contextObservedTransitionCount < 0
                || contextObservedTransitionCount > observedTransitionCount) {
            throw new IllegalArgumentException(
                    "context observations cannot exceed global observations"
            );
        }
    }

    /**
     * Whether the context key distinguishes anything for this cursor type.
     *
     * <p>{@code false} when the key is {@code EMPTY}, which the context key
     * factory produces for all but two normalized types.</p>
     *
     * <p>This matters more than it looks. {@code EMPTY} is a real bucket that
     * accumulates like any other, so {@code basis} flips to {@code CONTEXTUAL}
     * for <em>any</em> cursor type once that bucket has weight. A
     * {@code CONTEXTUAL} basis with {@code contextDistinguishes() == false}
     * therefore means "evidence from the catch-all bucket", not "evidence from
     * a narrower situation". Reading {@code basis} alone would overstate the
     * evidence, which is exactly why both travel together.</p>
     */
    public boolean contextDistinguishes() {
        return !ContextKey.EMPTY.equals(contextKey);
    }

    private static void requireProbability(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be between zero and one"
            );
        }
    }
}
