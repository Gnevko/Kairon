package kairon.system;

import java.util.Objects;

/**
 * One link of a body's parent chain: what it orbits, and which body that is.
 *
 * @param kind   what the journal called the parent
 * @param bodyId the parent's body id within this system
 */
public record BodyParent(ParentKind kind, long bodyId) {

    public BodyParent {
        kind = Objects.requireNonNull(kind, "kind");
        if (bodyId < 0) {
            throw new IllegalArgumentException(
                    "bodyId must not be negative: " + bodyId
            );
        }
    }
}
