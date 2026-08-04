package kairon.behavior.graph;

import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, aggregate-only read model for behavior graph visualization.
 *
 * <p>No exact event occurrences, occurrence payloads, or episode timelines
 * cross this read boundary. Node and edge topology and edge statistics are
 * global for the ship graph. Node counts are aggregates of the active episode
 * only.</p>
 */
public record BehaviorGraphVisualizationSnapshot(
        GraphId graphId,
        String shipDisplayName,
        long graphVersion,
        long topologyVersion,
        Instant evaluationTime,
        Optional<NormalizedEventType> currentEventType,
        Optional<EventOccurrenceId> currentOccurrenceId,
        Optional<SystemEpisodeId> activeEpisodeId,
        List<VisualizationNode> nodes,
        List<VisualizationEdge> edges
) {

    public BehaviorGraphVisualizationSnapshot {
        Objects.requireNonNull(graphId, "graphId");
        shipDisplayName = requireNonBlank(
                shipDisplayName,
                "shipDisplayName"
        );
        if (graphVersion < 0) {
            throw new IllegalArgumentException(
                    "graphVersion must be nonnegative"
            );
        }
        if (topologyVersion < 0) {
            throw new IllegalArgumentException(
                    "topologyVersion must be nonnegative"
            );
        }
        evaluationTime = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime"
        );
        currentEventType = Objects.requireNonNull(
                currentEventType,
                "currentEventType"
        );
        currentOccurrenceId = Objects.requireNonNull(
                currentOccurrenceId,
                "currentOccurrenceId"
        );
        activeEpisodeId = Objects.requireNonNull(
                activeEpisodeId,
                "activeEpisodeId"
        );
        if (currentEventType.isPresent()
                != currentOccurrenceId.isPresent()) {
            throw new IllegalArgumentException(
                    "current cursor type and occurrence must both be present "
                            + "or absent"
            );
        }
        if (currentEventType.isPresent() && activeEpisodeId.isEmpty()) {
            throw new IllegalArgumentException(
                    "a current cursor requires an active episode"
            );
        }
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }

    public record VisualizationNode(
            NormalizedEventType eventType,
            String displayName,
            long activeEpisodeOccurrenceCount
    ) {

        public VisualizationNode {
            Objects.requireNonNull(eventType, "eventType");
            displayName = requireNonBlank(displayName, "displayName");
            if (activeEpisodeOccurrenceCount < 0) {
                throw new IllegalArgumentException(
                        "activeEpisodeOccurrenceCount must be nonnegative"
                );
            }
        }
    }

    public record VisualizationEdge(
            NormalizedEventType from,
            NormalizedEventType to,
            long rawCount,
            double effectiveWeight
    ) {

        public VisualizationEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (rawCount < 1) {
                throw new IllegalArgumentException(
                        "rawCount must be positive"
                );
            }
            if (!Double.isFinite(effectiveWeight)
                    || effectiveWeight < 0.0) {
                throw new IllegalArgumentException(
                        "effectiveWeight must be finite and nonnegative"
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
