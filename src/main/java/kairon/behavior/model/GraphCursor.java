package kairon.behavior.model;

import kairon.behavior.normalize.NormalizedEventType;

import java.time.Instant;
import java.util.Objects;

public record GraphCursor(
        GraphId graphId,
        SystemEpisodeId episodeId,
        EventOccurrenceId occurrenceId,
        NormalizedEventType eventType,
        Instant updatedAt
) {

    public GraphCursor {
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(episodeId, "episodeId");
        Objects.requireNonNull(occurrenceId, "occurrenceId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
