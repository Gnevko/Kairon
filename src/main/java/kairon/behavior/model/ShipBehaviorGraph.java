package kairon.behavior.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Per-ship aggregate. Exact occurrences remain in SystemEpisode files.
 */
public record ShipBehaviorGraph(
        String schemaVersion,
        GraphId graphId,
        String shipType,
        String shipName,
        String loadoutHash,
        List<EventTypeNode> nodes,
        List<TransitionEdge> edges,
        List<SystemEpisodeSummary> episodes,
        GraphCursor cursor
) {

    public static final String SCHEMA_VERSION = "kairon.behavior-graph/v1";

    public ShipBehaviorGraph {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported ShipBehaviorGraph schemaVersion"
            );
        }
        Objects.requireNonNull(graphId, "graphId");
        nodes = sortedUnique(nodes, Comparator.naturalOrder(), "nodes");
        edges = sortedUnique(edges, Comparator.naturalOrder(), "edges");
        episodes = sortedUnique(
                episodes,
                Comparator.naturalOrder(),
                "episodes"
        );
        if (cursor != null && !cursor.graphId().equals(graphId)) {
            throw new IllegalArgumentException(
                    "cursor belongs to another graph"
            );
        }
    }

    public static ShipBehaviorGraph empty(
            GraphId graphId,
            String shipType,
            String shipName,
            String loadoutHash
    ) {
        return new ShipBehaviorGraph(
                SCHEMA_VERSION,
                graphId,
                shipType,
                shipName,
                loadoutHash,
                List.of(),
                List.of(),
                List.of(),
                null
        );
    }

    public ShipBehaviorGraph withShipMetadata(
            String newShipType,
            String newShipName,
            String newLoadoutHash
    ) {
        return new ShipBehaviorGraph(
                schemaVersion,
                graphId,
                newShipType,
                newShipName,
                newLoadoutHash,
                nodes,
                edges,
                episodes,
                cursor
        );
    }

    public ShipBehaviorGraph recordOccurrence(EventOccurrence occurrence) {
        Objects.requireNonNull(occurrence, "occurrence");
        if (!occurrence.graphId().equals(graphId)) {
            throw new IllegalArgumentException(
                    "occurrence belongs to another graph"
            );
        }
        List<EventTypeNode> updated = new ArrayList<>(nodes);
        int found = findNode(updated, occurrence);
        if (found >= 0) {
            updated.set(
                    found,
                    updated.get(found).recordOccurrence(occurrence.timestamp())
            );
        } else {
            updated.add(EventTypeNode.first(
                    occurrence.eventType(),
                    occurrence.timestamp()
            ));
        }
        return copy(updated, edges, episodes, cursor);
    }

    public ShipBehaviorGraph recordTransition(
            OccurrenceTransition transition,
            Duration halfLife
    ) {
        Objects.requireNonNull(transition, "transition");
        List<TransitionEdge> updated = new ArrayList<>(edges);
        EdgeKey key = new EdgeKey(
                transition.fromEventType(),
                transition.toEventType()
        );
        int found = findEdge(updated, key);
        if (found >= 0) {
            updated.set(
                    found,
                    updated.get(found).record(
                            transition.contextKey(),
                            transition.observedAt(),
                            halfLife
                    )
            );
        } else {
            updated.add(TransitionEdge.first(
                    key,
                    transition.contextKey(),
                    transition.observedAt(),
                    halfLife
            ));
        }
        return copy(nodes, updated, episodes, cursor);
    }

    public ShipBehaviorGraph withEpisode(SystemEpisode episode) {
        Objects.requireNonNull(episode, "episode");
        if (!episode.graphId().equals(graphId)) {
            throw new IllegalArgumentException(
                    "episode belongs to another graph"
            );
        }
        List<SystemEpisodeSummary> updated = new ArrayList<>(episodes);
        SystemEpisodeSummary summary = SystemEpisodeSummary.from(episode);
        updated.removeIf(existing -> existing.id().equals(episode.id()));
        updated.add(summary);
        return copy(nodes, edges, updated, cursor);
    }

    public ShipBehaviorGraph withCursor(GraphCursor newCursor) {
        return copy(nodes, edges, episodes, newCursor);
    }

    public TransitionEdge edge(EdgeKey key) {
        return edges.stream()
                .filter(edge -> edge.key().equals(key))
                .findFirst()
                .orElse(null);
    }

    private ShipBehaviorGraph copy(
            List<EventTypeNode> newNodes,
            List<TransitionEdge> newEdges,
            List<SystemEpisodeSummary> newEpisodes,
            GraphCursor newCursor
    ) {
        return new ShipBehaviorGraph(
                schemaVersion,
                graphId,
                shipType,
                shipName,
                loadoutHash,
                newNodes,
                newEdges,
                newEpisodes,
                newCursor
        );
    }

    private static int findNode(
            List<EventTypeNode> nodes,
            EventOccurrence occurrence
    ) {
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index).eventType().equals(occurrence.eventType())) {
                return index;
            }
        }
        return -1;
    }

    private static int findEdge(List<TransitionEdge> edges, EdgeKey key) {
        for (int index = 0; index < edges.size(); index++) {
            if (edges.get(index).key().equals(key)) {
                return index;
            }
        }
        return -1;
    }

    private static <T> List<T> sortedUnique(
            List<T> values,
            Comparator<T> comparator,
            String name
    ) {
        Objects.requireNonNull(values, name);
        List<T> copy = new ArrayList<>(values);
        copy.sort(comparator);
        for (int index = 1; index < copy.size(); index++) {
            if (comparator.compare(copy.get(index - 1), copy.get(index)) == 0) {
                throw new IllegalArgumentException(
                        name + " must not contain duplicate keys"
                );
            }
        }
        return List.copyOf(copy);
    }
}
