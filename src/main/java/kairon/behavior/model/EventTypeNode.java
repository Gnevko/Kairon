package kairon.behavior.model;

import kairon.behavior.normalize.NormalizedEventType;

import java.time.Instant;
import java.util.Objects;

/**
 * Structural event type in one global per-ship graph.
 *
 * <p>{@code rawOccurrenceCount} is a compact historical diagnostic across all
 * episodes of this {@link GraphId}. Concrete occurrences, including the
 * active-episode instances shown by the UI, belong to
 * {@link SystemEpisode}.</p>
 */
public record EventTypeNode(
        NormalizedEventType eventType,
        long rawOccurrenceCount,
        Instant firstSeenAt,
        Instant lastSeenAt
) implements Comparable<EventTypeNode> {

    public EventTypeNode {
        Objects.requireNonNull(eventType, "eventType");
        if (rawOccurrenceCount < 1) {
            throw new IllegalArgumentException(
                    "rawOccurrenceCount must be positive"
            );
        }
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    }

    public static EventTypeNode first(
            NormalizedEventType eventType,
            Instant observedAt
    ) {
        return new EventTypeNode(eventType, 1, observedAt, observedAt);
    }

    public EventTypeNode recordOccurrence(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        return new EventTypeNode(
                eventType,
                rawOccurrenceCount + 1,
                firstSeenAt.isAfter(observedAt) ? observedAt : firstSeenAt,
                lastSeenAt.isBefore(observedAt) ? observedAt : lastSeenAt
        );
    }

    @Override
    public int compareTo(EventTypeNode other) {
        return eventType.compareTo(other.eventType);
    }
}
