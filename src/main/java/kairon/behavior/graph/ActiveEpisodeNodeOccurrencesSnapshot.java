package kairon.behavior.graph;

import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable occurrence-list snapshot for one structural event type.
 *
 * <p>{@code episodeVersion} is the active episode timeline size. It changes
 * whenever that episode accepts an occurrence and is zero when no active
 * episode exists.</p>
 */
public record ActiveEpisodeNodeOccurrencesSnapshot(
        GraphId graphId,
        Optional<SystemEpisodeId> activeEpisodeId,
        NormalizedEventType eventType,
        String displayName,
        long graphVersion,
        long episodeVersion,
        List<EventOccurrenceSummary> occurrences
) {

    public ActiveEpisodeNodeOccurrencesSnapshot {
        Objects.requireNonNull(graphId, "graphId");
        activeEpisodeId = Objects.requireNonNull(
                activeEpisodeId,
                "activeEpisodeId"
        );
        Objects.requireNonNull(eventType, "eventType");
        displayName = requireNonBlank(displayName, "displayName");
        if (graphVersion < 0 || episodeVersion < 0) {
            throw new IllegalArgumentException(
                    "versions must be nonnegative"
            );
        }
        occurrences = List.copyOf(
                Objects.requireNonNull(occurrences, "occurrences")
        );
        if (activeEpisodeId.isEmpty()
                && (!occurrences.isEmpty() || episodeVersion != 0)) {
            throw new IllegalArgumentException(
                    "an absent active episode must have no rows or version"
            );
        }
        for (int index = 1; index < occurrences.size(); index++) {
            if (occurrences.get(index - 1)
                    .compareTo(occurrences.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "occurrences must use unique canonical episode order"
                );
            }
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
