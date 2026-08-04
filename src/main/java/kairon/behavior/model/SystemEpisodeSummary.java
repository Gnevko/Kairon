package kairon.behavior.model;

import java.time.Instant;
import java.util.Objects;

public record SystemEpisodeSummary(
        SystemEpisodeId id,
        long systemAddress,
        String systemName,
        Instant startedAt,
        Instant completedAt,
        EpisodeEntrySource entrySource,
        EpisodeCompletionReason completionReason,
        EventOccurrenceId rootOccurrenceId,
        int occurrenceCount
) implements Comparable<SystemEpisodeSummary> {

    public SystemEpisodeSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(systemName, "systemName");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(entrySource, "entrySource");
        // A restored visit has no root at all, and none of its occurrences
        // becomes one; only a rooted episode must be non-empty. See
        // SystemEpisode.
        if (occurrenceCount < 0
                || rootOccurrenceId != null && occurrenceCount < 1) {
            throw new IllegalArgumentException(
                    "a rooted episode summary must count its root"
            );
        }
        if ((completedAt == null) != (completionReason == null)) {
            throw new IllegalArgumentException(
                    "completion time and reason must both be present or absent"
            );
        }
    }

    public static SystemEpisodeSummary from(SystemEpisode episode) {
        return new SystemEpisodeSummary(
                episode.id(),
                episode.systemAddress(),
                episode.systemName(),
                episode.startedAt(),
                episode.completedAt(),
                episode.entrySource(),
                episode.completionReason(),
                episode.rootOccurrenceId(),
                episode.timeline().size()
        );
    }

    @Override
    public int compareTo(SystemEpisodeSummary other) {
        int timeOrder = startedAt.compareTo(other.startedAt);
        return timeOrder != 0 ? timeOrder : id.compareTo(other.id);
    }
}
