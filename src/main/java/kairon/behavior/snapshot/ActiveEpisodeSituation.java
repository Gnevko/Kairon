package kairon.behavior.snapshot;

import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Exact immutable path through the active episode at one graph revision.
 */
public record ActiveEpisodeSituation(
        GraphId graphId,
        SystemEpisodeId episodeId,
        long systemAddress,
        String systemName,
        Instant startedAt,
        GraphCursor cursor,
        List<SituationOccurrence> trajectory,
        SituationOccurrence currentOccurrence,
        long totalOccurrenceCount,
        Map<NormalizedEventType, Long> occurrenceCounts,
        long graphVersion,
        long topologyVersion
) {

    public ActiveEpisodeSituation {
        graphId = Objects.requireNonNull(graphId, "graphId");
        episodeId = Objects.requireNonNull(episodeId, "episodeId");
        systemName = requireNonBlank(systemName, "systemName");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        cursor = Objects.requireNonNull(cursor, "cursor");
        trajectory = List.copyOf(
                Objects.requireNonNull(trajectory, "trajectory")
        );
        currentOccurrence = Objects.requireNonNull(
                currentOccurrence,
                "currentOccurrence"
        );
        if (trajectory.isEmpty()) {
            throw new IllegalArgumentException(
                    "trajectory must not be empty"
            );
        }
        if (totalOccurrenceCount != trajectory.size()) {
            throw new IllegalArgumentException(
                    "totalOccurrenceCount must equal trajectory size"
            );
        }
        if (graphVersion < 0 || topologyVersion < 0) {
            throw new IllegalArgumentException(
                    "graph revisions must be nonnegative"
            );
        }
        requireCursorOwnership(
                graphId,
                episodeId,
                cursor,
                currentOccurrence
        );
        requireCanonicalTrajectory(
                trajectory,
                currentOccurrence,
                startedAt
        );
        occurrenceCounts = immutableCounts(
                occurrenceCounts,
                trajectory
        );
    }

    private static void requireCursorOwnership(
            GraphId graphId,
            SystemEpisodeId episodeId,
            GraphCursor cursor,
            SituationOccurrence currentOccurrence
    ) {
        if (!cursor.graphId().equals(graphId)
                || !cursor.episodeId().equals(episodeId)
                || !cursor.occurrenceId().equals(
                        currentOccurrence.occurrenceId()
                )
                || !cursor.eventType().equals(
                        currentOccurrence.eventType()
                )
                || !cursor.updatedAt().equals(
                        currentOccurrence.occurredAt()
                )) {
            throw new IllegalArgumentException(
                    "current occurrence must match the graph cursor"
            );
        }
    }

    private static void requireCanonicalTrajectory(
            List<SituationOccurrence> trajectory,
            SituationOccurrence currentOccurrence,
            Instant startedAt
    ) {
        int currentCount = 0;
        var occurrenceIds = new HashSet<EventOccurrenceId>();
        for (int index = 0; index < trajectory.size(); index++) {
            SituationOccurrence occurrence = Objects.requireNonNull(
                    trajectory.get(index),
                    "trajectory occurrence"
            );
            if (occurrence.episodeSequence() != index) {
                throw new IllegalArgumentException(
                        "trajectory must use contiguous episode order"
                );
            }
            if (!occurrenceIds.add(occurrence.occurrenceId())) {
                throw new IllegalArgumentException(
                        "trajectory occurrence IDs must be unique"
                );
            }
            if (occurrence.current()) {
                currentCount++;
            }
        }
        /*
         * Two shapes, told apart by what the first occurrence is.
         *
         * An episode opened by an arrival begins with that arrival, and the
         * visit began exactly when it happened. An episode restored from a
         * session already in progress has no arrival at all: the visit began
         * when the Commander was found here, and its first occurrence is
         * whatever they did next. SYSTEM_ENTRY is only ever minted as an
         * episode root, so it can never be that first occurrence.
         */
        SituationOccurrence root = trajectory.getFirst();
        boolean entered =
                root.eventType().equals(NormalizedEventType.SYSTEM_ENTRY);
        if (entered
                ? !root.occurredAt().equals(startedAt)
                : root.occurredAt().isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "trajectory must begin no earlier than the visit did"
            );
        }
        if (currentCount != 1
                || !trajectory.getLast().equals(currentOccurrence)
                || !currentOccurrence.current()) {
            throw new IllegalArgumentException(
                    "trajectory must end at its one current occurrence"
            );
        }
    }

    private static Map<NormalizedEventType, Long> immutableCounts(
            Map<NormalizedEventType, Long> supplied,
            List<SituationOccurrence> trajectory
    ) {
        Objects.requireNonNull(supplied, "occurrenceCounts");
        TreeMap<NormalizedEventType, Long> copy = new TreeMap<>();
        supplied.forEach((eventType, count) -> {
            Objects.requireNonNull(eventType, "occurrence count event type");
            Objects.requireNonNull(count, "occurrence count");
            if (count <= 0) {
                throw new IllegalArgumentException(
                        "occurrence counts must be positive"
                );
            }
            copy.put(eventType, count);
        });
        TreeMap<NormalizedEventType, Long> derived = new TreeMap<>();
        for (SituationOccurrence occurrence : trajectory) {
            derived.merge(occurrence.eventType(), 1L, Long::sum);
        }
        if (!copy.equals(derived)) {
            throw new IllegalArgumentException(
                    "occurrenceCounts must match trajectory"
            );
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
