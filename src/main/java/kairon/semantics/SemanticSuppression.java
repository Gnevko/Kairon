package kairon.semantics;

import java.util.Objects;

/**
 * An immutable, downstream-visible record that semantic detail was dropped.
 *
 * <p>Suppression must never be silent, never log-only, and never held in a
 * counter no consumer can read. Whenever the accumulator folds envelopes under
 * its memory bound, the drained result carries one of these so a consumer can
 * state exactly how much was lost and over which span.</p>
 *
 * <p>Canonical state changes are never suppressed: folding preserves the net
 * per-field transition. Only structured facts are dropped, and only with this
 * marker attached.</p>
 *
 * @param reason                     why detail was dropped
 * @param suppressedFactCount        structured facts dropped; may be zero when
 *                                   folded envelopes carried only state changes
 * @param coalescedEnvelopeCount     envelopes folded, always at least one
 * @param firstSuppressedBusSequence lowest bus sequence in the folded span
 * @param lastSuppressedBusSequence  highest bus sequence in the folded span
 */
public record SemanticSuppression(
        Reason reason,
        int suppressedFactCount,
        int coalescedEnvelopeCount,
        long firstSuppressedBusSequence,
        long lastSuppressedBusSequence
) {

    public SemanticSuppression {
        reason = Objects.requireNonNull(reason, "reason");
        if (suppressedFactCount < 0) {
            throw new IllegalArgumentException(
                    "suppressedFactCount must be nonnegative"
            );
        }
        if (coalescedEnvelopeCount < 1) {
            throw new IllegalArgumentException(
                    "a suppression marker requires at least one folded envelope"
            );
        }
        if (firstSuppressedBusSequence < 1
                || lastSuppressedBusSequence < firstSuppressedBusSequence) {
            throw new IllegalArgumentException(
                    "suppressed bus sequence range is invalid"
            );
        }
    }

    /** Why semantic detail was dropped. Closed; never a free-form string. */
    public enum Reason {

        /**
         * The accumulator exceeded its retained-envelope bound and folded the
         * oldest envelopes into a per-field coalesced set.
         */
        MEMORY_BOUND_COALESCING
    }
}
