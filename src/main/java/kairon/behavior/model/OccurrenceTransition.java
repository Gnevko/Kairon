package kairon.behavior.model;

import kairon.behavior.normalize.NormalizedEventType;

import java.time.Instant;
import java.util.Objects;

/**
 * One historical transition between adjacent occurrences in one episode.
 */
public record OccurrenceTransition(
        TransitionOccurrenceId id,
        SystemEpisodeId episodeId,
        EventOccurrenceId fromOccurrenceId,
        EventOccurrenceId toOccurrenceId,
        NormalizedEventType fromEventType,
        NormalizedEventType toEventType,
        Instant observedAt,
        ContextKey contextKey
) implements Comparable<OccurrenceTransition> {

    public OccurrenceTransition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(episodeId, "episodeId");
        Objects.requireNonNull(fromOccurrenceId, "fromOccurrenceId");
        Objects.requireNonNull(toOccurrenceId, "toOccurrenceId");
        Objects.requireNonNull(fromEventType, "fromEventType");
        Objects.requireNonNull(toEventType, "toEventType");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(contextKey, "contextKey");
        if (fromOccurrenceId.equals(toOccurrenceId)) {
            throw new IllegalArgumentException(
                    "transition occurrence endpoints must differ"
            );
        }
    }

    @Override
    public int compareTo(OccurrenceTransition other) {
        int timeOrder = observedAt.compareTo(other.observedAt);
        return timeOrder != 0 ? timeOrder : id.compareTo(other.id);
    }
}
