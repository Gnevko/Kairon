package kairon.semantics;

import java.util.Objects;

/**
 * One exact canonical field change with its provenance.
 *
 * <p>Computed inside the projection boundary while the previous snapshot and
 * the current snapshot are both still available. Downstream must never
 * recompute or reconstruct it.</p>
 *
 * <p>It carries no statement about where the value came from. That field
 * existed to separate a fact this observation wrote from one served out of the
 * projector's per-body store, and there is no such store any more: canonical
 * state answers where the Commander is, and what a body is like is the
 * current-system registry's ({@code ADR-0025}).</p>
 */
public record SemanticStateChange(
        SemanticField field,
        SemanticValue before,
        SemanticValue after,
        SemanticChangeKind changeKind,
        SemanticProvenance provenance
) {

    public SemanticStateChange {
        field = Objects.requireNonNull(field, "field");
        before = Objects.requireNonNull(before, "before");
        after = Objects.requireNonNull(after, "after");
        changeKind = Objects.requireNonNull(changeKind, "changeKind");
        provenance = Objects.requireNonNull(provenance, "provenance");
        if (before.equals(after)) {
            throw new IllegalArgumentException(
                    "an unchanged value must not produce a state change"
            );
        }
        if (!field.answeredByCanonicalState()) {
            throw new IllegalArgumentException(
                    "a field canonical state does not establish cannot change"
            );
        }
        requireConsistentKind(before, after, changeKind);
    }

    public SemanticSubject subject() {
        return field.subject();
    }

    private static void requireConsistentKind(
            SemanticValue before,
            SemanticValue after,
            SemanticChangeKind changeKind
    ) {
        switch (changeKind) {
            case ESTABLISHED -> {
                if (before.known() || !after.known()) {
                    throw new IllegalArgumentException(
                            "ESTABLISHED requires unknown before and known after"
                    );
                }
            }
            case UPDATED -> {
                if (!before.known() || !after.known()) {
                    throw new IllegalArgumentException(
                            "UPDATED requires known before and known after"
                    );
                }
            }
            case CLEARED -> {
                if (!before.known() || after.known()) {
                    throw new IllegalArgumentException(
                            "CLEARED requires known before and unknown after"
                    );
                }
            }
        }
    }
}
