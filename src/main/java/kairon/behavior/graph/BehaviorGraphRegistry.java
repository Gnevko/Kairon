package kairon.behavior.graph;

import kairon.behavior.model.GraphId;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.persistence.BehaviorGraphStore;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Process-local cache of independent per-ship aggregates backed by a store.
 */
public final class BehaviorGraphRegistry {

    private final BehaviorGraphStore store;
    private final Map<GraphId, ShipBehaviorGraph> graphs = new TreeMap<>();

    public BehaviorGraphRegistry(BehaviorGraphStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public GraphResolution getOrCreate(
            GraphId graphId,
            String shipType,
            String shipName,
            String loadoutHash
    ) {
        Objects.requireNonNull(graphId, "graphId");
        ShipBehaviorGraph cached = graphs.get(graphId);
        if (cached != null) {
            ShipBehaviorGraph updated = cached.withShipMetadata(
                    valueOrExisting(shipType, cached.shipType()),
                    valueOrExisting(shipName, cached.shipName()),
                    valueOrExisting(loadoutHash, cached.loadoutHash())
            );
            graphs.put(graphId, updated);
            return new GraphResolution(updated, false, false);
        }

        Optional<ShipBehaviorGraph> loaded = store.loadGraph(graphId);
        if (loaded.isPresent()) {
            ShipBehaviorGraph graph = loaded.orElseThrow().withShipMetadata(
                    valueOrExisting(shipType, loaded.orElseThrow().shipType()),
                    valueOrExisting(shipName, loaded.orElseThrow().shipName()),
                    valueOrExisting(
                            loadoutHash,
                            loaded.orElseThrow().loadoutHash()
                    )
            );
            graphs.put(graphId, graph);
            return new GraphResolution(graph, false, true);
        }

        ShipBehaviorGraph graph = ShipBehaviorGraph.empty(
                graphId,
                shipType,
                shipName,
                loadoutHash
        );
        graphs.put(graphId, graph);
        return new GraphResolution(graph, true, false);
    }

    public Optional<ShipBehaviorGraph> find(GraphId graphId) {
        ShipBehaviorGraph cached = graphs.get(graphId);
        return cached == null ? store.loadGraph(graphId) : Optional.of(cached);
    }

    public void replace(ShipBehaviorGraph graph) {
        graphs.put(
                Objects.requireNonNull(graph, "graph").graphId(),
                graph
        );
    }

    public Collection<ShipBehaviorGraph> loadedGraphs() {
        return ListCopy.copyOf(graphs.values());
    }

    private static String valueOrExisting(String candidate, String existing) {
        return candidate == null ? existing : candidate;
    }

    public record GraphResolution(
            ShipBehaviorGraph graph,
            boolean created,
            boolean restored
    ) {
    }

    /**
     * Avoids exposing a mutable view while keeping Java 21-only dependencies.
     */
    private static final class ListCopy {

        private static <T> Collection<T> copyOf(Collection<T> values) {
            return java.util.List.copyOf(values);
        }
    }
}
