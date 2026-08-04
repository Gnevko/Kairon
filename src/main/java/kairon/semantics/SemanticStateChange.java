package kairon.semantics;

import java.util.Objects;

/**
 * One exact canonical field change with its provenance.
 *
 * <p>Computed inside the projection boundary while the previous snapshot, the
 * current snapshot and the projector write path are all still available.
 * Downstream must never recompute or reconstruct it.</p>
 */
public record SemanticStateChange(
        SemanticField field,
        SemanticValue before,
        SemanticValue after,
        SemanticChangeKind changeKind,
        SemanticValueOrigin origin,
        SemanticProvenance provenance
) {

    public SemanticStateChange {
        field = Objects.requireNonNull(field, "field");
        before = Objects.requireNonNull(before, "before");
        after = Objects.requireNonNull(after, "after");
        changeKind = Objects.requireNonNull(changeKind, "changeKind");
        origin = Objects.requireNonNull(origin, "origin");
        provenance = Objects.requireNonNull(provenance, "provenance");
        if (before.equals(after)) {
            throw new IllegalArgumentException(
                    "an unchanged value must not produce a state change"
            );
        }
        requireConsistentKind(field, before, after, changeKind, origin);
    }

    public SemanticSubject subject() {
        return field.subject();
    }

    private static void requireConsistentKind(
            SemanticField field,
            SemanticValue before,
            SemanticValue after,
            SemanticChangeKind changeKind,
            SemanticValueOrigin origin
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
            case ACTIVATED_FROM_CONTEXT -> {
                if (!after.known()) {
                    throw new IllegalArgumentException(
                            "ACTIVATED_FROM_CONTEXT requires a known after"
                    );
                }
                if (origin != SemanticValueOrigin.STORED_CONTEXT) {
                    throw new IllegalArgumentException(
                            "ACTIVATED_FROM_CONTEXT requires STORED_CONTEXT "
                                    + "origin"
                    );
                }
                if (!field.bodyRegistryDerived()) {
                    throw new IllegalArgumentException(
                            "ACTIVATED_FROM_CONTEXT requires a registry-derived "
                                    + "field"
                    );
                }
            }
        }
    }
}
