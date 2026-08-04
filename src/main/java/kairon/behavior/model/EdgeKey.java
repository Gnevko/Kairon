package kairon.behavior.model;

import kairon.behavior.normalize.NormalizedEventType;

import java.util.Objects;

public record EdgeKey(
        NormalizedEventType fromEventType,
        NormalizedEventType toEventType
) implements Comparable<EdgeKey> {

    public EdgeKey {
        Objects.requireNonNull(fromEventType, "fromEventType");
        Objects.requireNonNull(toEventType, "toEventType");
    }

    @Override
    public int compareTo(EdgeKey other) {
        int fromOrder = fromEventType.compareTo(other.fromEventType);
        return fromOrder != 0
                ? fromOrder
                : toEventType.compareTo(other.toEventType);
    }

    @Override
    public String toString() {
        return fromEventType + "->" + toEventType;
    }
}
