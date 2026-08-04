package kairon.behavior.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * Canonical, deliberately low-cardinality transition context.
 */
public record ContextKey(@JsonValue String canonical)
        implements Comparable<ContextKey> {

    public static final ContextKey EMPTY = new ContextKey("EMPTY");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ContextKey {
        canonical = Objects.requireNonNull(canonical, "canonical");
        if (canonical.isBlank()) {
            throw new IllegalArgumentException("canonical must not be blank");
        }
    }

    @Override
    public int compareTo(ContextKey other) {
        return canonical.compareTo(other.canonical);
    }

    @Override
    public String toString() {
        return canonical;
    }
}
