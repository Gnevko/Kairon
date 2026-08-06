package kairon.semantics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Retains the semantic effects that occurred between two model turns.
 *
 * <p>Exists because the effects of {@code CONTEXT_ONLY}, {@code STATUS} and
 * {@code CONTROL} observations survive today only as a final value: what
 * caused them is discarded. This accumulator keeps the provenance so a later
 * turn can say which observation established what.</p>
 *
 * <h2>Ownership and threading</h2>
 * <p>Owned by a single component that also owns turn boundaries. It is
 * deliberately <em>not</em> thread-safe: its owner drains it on the same single
 * thread that records into it, so no concurrent collection is warranted. That
 * keeps replay deterministic.</p>
 *
 * <h2>Memory bound</h2>
 * <p>Bounded, and never silently lossy. Up to {@code maxRetainedEnvelopes}
 * envelopes are retained whole. Beyond that, the oldest envelope's canonical
 * state changes are folded into a coalesced set keyed by field — preserving the
 * earliest {@code before}, the latest {@code after} and the latest provenance —
 * which is bounded by the number of canonical fields. Structured facts dropped
 * during coalescing are counted and reported. Gameplay state changes are never
 * evicted.</p>
 */
public final class SemanticEffectAccumulator {

    public static final int DEFAULT_MAX_RETAINED_ENVELOPES = 512;

    private final int maxRetainedEnvelopes;
    private final Deque<SemanticObservationEnvelope> retained =
            new ArrayDeque<>();
    private final Map<SemanticField, SemanticStateChange> coalesced =
            new LinkedHashMap<>();

    private int suppressedFactCount;
    private int coalescedEnvelopeCount;
    private long firstSuppressedBusSequence;
    private long lastSuppressedBusSequence;
    private long lastRecordedBusSequence;

    public SemanticEffectAccumulator() {
        this(DEFAULT_MAX_RETAINED_ENVELOPES);
    }

    public SemanticEffectAccumulator(int maxRetainedEnvelopes) {
        if (maxRetainedEnvelopes < 1) {
            throw new IllegalArgumentException(
                    "maxRetainedEnvelopes must be positive"
            );
        }
        this.maxRetainedEnvelopes = maxRetainedEnvelopes;
    }

    /**
     * Records one published envelope in bus order.
     *
     * <p>Every envelope advances the bus-order check, including one that is not
     * kept: declining to retain an effect is not the same as never having seen
     * the observation.</p>
     *
     * <p>A {@code RESTORE_ONLY} envelope is not retained at all. A
     * {@code DIAGNOSTIC_ONLY} one is retained only when it actually changed
     * canonical state: merely having crossed the bus is not a reason to enter
     * model context.</p>
     */
    public void record(SemanticObservationEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.busSequence() <= lastRecordedBusSequence) {
            throw new IllegalStateException(
                    "semantic effects must be recorded in ascending bus order: "
                            + envelope.busSequence()
                            + " after "
                            + lastRecordedBusSequence
            );
        }
        lastRecordedBusSequence = envelope.busSequence();
        if (!retains(envelope)) {
            return;
        }
        while (retained.size() >= maxRetainedEnvelopes) {
            coalesceOldest();
        }
        retained.addLast(envelope);
    }

    /**
     * Removes and returns every effect up to and including
     * {@code throughBusSequence}.
     *
     * <p>Effects newer than the boundary stay for the next turn. Drained
     * effects never reappear.</p>
     */
    public Drained drainThrough(long throughBusSequence) {
        List<SemanticStateChange> coalescedChanges = new ArrayList<>();
        coalesced.values().removeIf(change -> {
            if (change.provenance().busSequence() <= throughBusSequence) {
                coalescedChanges.add(change);
                return true;
            }
            return false;
        });

        List<SemanticObservationEnvelope> drained = new ArrayList<>();
        while (!retained.isEmpty()
                && retained.peekFirst().busSequence()
                <= throughBusSequence) {
            drained.add(retained.removeFirst());
        }

        Optional<SemanticSuppression> suppression = coalescedEnvelopeCount == 0
                ? Optional.empty()
                : Optional.of(new SemanticSuppression(
                        SemanticSuppression.Reason.MEMORY_BOUND_COALESCING,
                        suppressedFactCount,
                        coalescedEnvelopeCount,
                        firstSuppressedBusSequence,
                        lastSuppressedBusSequence
                ));
        suppressedFactCount = 0;
        coalescedEnvelopeCount = 0;
        firstSuppressedBusSequence = 0L;
        lastSuppressedBusSequence = 0L;
        return new Drained(
                List.copyOf(drained),
                List.copyOf(coalescedChanges),
                suppression
        );
    }

    /** Envelopes still held for a later turn. */
    public int pendingEnvelopeCount() {
        return retained.size();
    }

    /** Coalesced field changes still held for a later turn. */
    public int pendingCoalescedChangeCount() {
        return coalesced.size();
    }

    public boolean isEmpty() {
        return retained.isEmpty() && coalesced.isEmpty();
    }

    /**
     * Whether an envelope belongs in the next turn's context.
     *
     * <p>A {@code RESTORE_ONLY} observation is never retained, whatever its
     * role and whatever it changed. Its effects restored what was already true
     * before Kairon was listening; carrying them forward is how a bootstrap
     * arrival came to be reported as a change at the moment the Commander
     * landed. The state it established is not lost — it is canonical, and a
     * later turn reads it as standing background through {@code context}, which
     * is what background is for.</p>
     *
     * <p>The question this asks is retention, not visibility. A live
     * {@code CONTEXT_ONLY} record is model-silent and stays; only capture mode
     * decides retention, which is why the two are separate values rather than
     * one.</p>
     *
     * <p>Otherwise the earlier rule stands. {@code NEW}, {@code CONTEXT_ONLY},
     * {@code STATUS} and {@code CONTROL} are retained, including when they
     * carry no fact and changed no state: that such an observation arrived at
     * all is itself provenance, and dropping it would silently rewrite what
     * happened between two turns. {@code DIAGNOSTIC_ONLY} is retained only when
     * it actually changed canonical state — merely having crossed the bus is
     * not a reason to enter model context.</p>
     */
    private static boolean retains(SemanticObservationEnvelope envelope) {
        if (envelope.effectRetention() == EffectRetention.RESTORE_ONLY) {
            return false;
        }
        return envelope.sourceRole() != SemanticSourceRole.DIAGNOSTIC_ONLY
                || envelope.changedState();
    }

    /**
     * Folds the oldest retained envelope into the coalesced field set.
     *
     * <p>For each field, keeps the earliest {@code before} and the latest
     * {@code after}, so the net transition across the folded span stays exact,
     * and carries the later provenance because that is the observation that
     * produced the value now in effect. A field that returned to its earlier
     * value produced no net change and is removed outright.</p>
     */
    private void coalesceOldest() {
        SemanticObservationEnvelope oldest = retained.removeFirst();
        coalescedEnvelopeCount++;
        suppressedFactCount += oldest.structuredFacts().size();
        if (firstSuppressedBusSequence == 0L) {
            firstSuppressedBusSequence = oldest.busSequence();
        }
        lastSuppressedBusSequence = oldest.busSequence();
        for (SemanticStateChange change : oldest.stateChanges()) {
            SemanticStateChange earlier = coalesced.get(change.field());
            if (earlier == null) {
                coalesced.put(change.field(), change);
                continue;
            }
            SemanticValue before = earlier.before();
            SemanticValue after = change.after();
            if (before.equals(after)) {
                coalesced.remove(change.field());
                continue;
            }
            coalesced.put(change.field(), new SemanticStateChange(
                    change.field(),
                    before,
                    after,
                    mergedKind(before, after),
                    change.provenance()
            ));
        }
    }

    private static SemanticChangeKind mergedKind(
            SemanticValue before,
            SemanticValue after
    ) {
        if (!after.known()) {
            return SemanticChangeKind.CLEARED;
        }
        return before.known()
                ? SemanticChangeKind.UPDATED
                : SemanticChangeKind.ESTABLISHED;
    }

    /**
     * The effects belonging to one turn.
     *
     * @param envelopes             whole envelopes, ascending bus order
     * @param coalescedStateChanges net field changes recovered from envelopes
     *                              folded away under the memory bound
     * @param coalescedEnvelopeCount how many envelopes were folded
     * @param suppressedFactCount    structured facts dropped while folding
     */
    public record Drained(
            List<SemanticObservationEnvelope> envelopes,
            List<SemanticStateChange> coalescedStateChanges,
            Optional<SemanticSuppression> suppression
    ) {

        public Drained {
            envelopes = List.copyOf(
                    Objects.requireNonNull(envelopes, "envelopes")
            );
            coalescedStateChanges = List.copyOf(Objects.requireNonNull(
                    coalescedStateChanges,
                    "coalescedStateChanges"
            ));
            suppression = Objects.requireNonNull(
                    suppression,
                    "suppression"
            );
        }

        public static Drained none() {
            return new Drained(List.of(), List.of(), Optional.empty());
        }

        public boolean empty() {
            return envelopes.isEmpty() && coalescedStateChanges.isEmpty();
        }

        /** Whether the memory bound forced any envelope to be folded. */
        public boolean bounded() {
            return suppression.isPresent();
        }
    }
}
