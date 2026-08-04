package kairon.behavior.model;

import kairon.behavior.normalize.NormalizedEventType;

import java.time.Instant;
import java.util.Objects;

public record TransitionEdgeView(
        NormalizedEventType fromEventType,
        NormalizedEventType toEventType,
        long rawCount,
        double effectiveWeight,
        double globalProbability,
        Instant firstSeenAt,
        Instant lastSeenAt
) {

    public TransitionEdgeView {
        Objects.requireNonNull(fromEventType, "fromEventType");
        Objects.requireNonNull(toEventType, "toEventType");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    }
}
