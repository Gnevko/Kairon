package kairon.behavior.graph;

import kairon.behavior.model.EventOccurrenceId;

import java.time.Instant;
import java.util.Objects;

/**
 * Lightweight immutable table row for one active-episode occurrence.
 */
public record EventOccurrenceSummary(
        EventOccurrenceId occurrenceId,
        Instant timestamp,
        long episodeSequence,
        long sourceSequence,
        String originalEventName
) implements Comparable<EventOccurrenceSummary> {

    public EventOccurrenceSummary {
        Objects.requireNonNull(occurrenceId, "occurrenceId");
        Objects.requireNonNull(timestamp, "timestamp");
        if (episodeSequence < 0) {
            throw new IllegalArgumentException(
                    "episodeSequence must be nonnegative"
            );
        }
        if (sourceSequence < 0) {
            throw new IllegalArgumentException(
                    "sourceSequence must be nonnegative"
            );
        }
        originalEventName = requireNonBlank(
                originalEventName,
                "originalEventName"
        );
    }

    @Override
    public int compareTo(EventOccurrenceSummary other) {
        int episodeOrder = Long.compare(
                episodeSequence,
                other.episodeSequence
        );
        return episodeOrder != 0
                ? episodeOrder
                : occurrenceId.compareTo(other.occurrenceId);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
