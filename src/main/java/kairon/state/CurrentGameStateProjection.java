package kairon.state;

import kairon.semantics.SemanticStateChange;

import java.util.List;
import java.util.Objects;

/**
 * Atomic immutable result of applying one observation to canonical state.
 *
 * <p>{@link AppliedObservation} owns the moment: which observation it was, how
 * it was captured, what it means, the state before and after, and the exact
 * field-level delta — none of which can be reconstructed downstream, because
 * {@code previousState} exists only inside this boundary. This record adds the
 * one thing that is not part of that moment: {@code changes}, the coarse facet
 * summary existing consumers read.</p>
 *
 * <p>The accessors below delegate rather than duplicate, so every reader keeps
 * the shape it had while there is exactly one owner of each fact.</p>
 */
public record CurrentGameStateProjection(
        AppliedObservation applied,
        CurrentGameStateChangeSet changes
) {

    public CurrentGameStateProjection {
        applied = Objects.requireNonNull(applied, "applied");
        changes = Objects.requireNonNull(changes, "changes");
        CurrentGameStateChangeSet expected =
                CurrentGameStateChangeSet.between(
                        applied.previousState(),
                        applied.currentState()
                );
        if (!changes.equals(expected)) {
            throw new IllegalArgumentException(
                    "changes must describe previousState to currentState"
            );
        }
    }

    public long busSequence() {
        return applied.busSequence();
    }

    public CurrentGameStateSnapshot previousState() {
        return applied.previousState();
    }

    public CurrentGameStateSnapshot currentState() {
        return applied.currentState();
    }

    /**
     * Technical event-local body context for behaviour occurrences.
     *
     * <p>Preserved without changing physical current state.</p>
     */
    public CurrentGameStateSnapshot observationContext() {
        return applied.observationContext();
    }

    /**
     * The exact field-level delta, with before/after values, change kind and
     * write-path origin.
     */
    public List<SemanticStateChange> semanticChanges() {
        return applied.semanticChanges();
    }
}
