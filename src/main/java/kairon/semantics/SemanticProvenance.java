package kairon.semantics;

import java.util.Objects;

/**
 * Which observation established a fact or change, and in what role.
 *
 * <p>Sufficient to answer, for any semantic datum: which observation
 * established it, what role that observation had, and which {@code busSequence}
 * it belongs to.</p>
 *
 * @param busSequence        the single correlation identity of the source
 *                           observation
 * @param sourceRole         the role the source observation had; taken from the
 *                           observation, never inferred from queue membership
 * @param rawObservationType the raw journal event name, or a non-journal
 *                           observation kind such as {@code Status}
 * @param observationId      stable identity of the source observation
 */
public record SemanticProvenance(
        long busSequence,
        SemanticSourceRole sourceRole,
        String rawObservationType,
        String observationId
) {

    public SemanticProvenance {
        if (busSequence < 1) {
            throw new IllegalArgumentException(
                    "busSequence must be positive"
            );
        }
        sourceRole = Objects.requireNonNull(sourceRole, "sourceRole");
        rawObservationType = requireNonBlank(
                rawObservationType,
                "rawObservationType"
        );
        observationId = requireNonBlank(observationId, "observationId");
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
