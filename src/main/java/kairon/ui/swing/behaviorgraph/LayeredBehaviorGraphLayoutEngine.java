package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationEdge;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationNode;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.ui.swing.behaviorgraph.BehaviorGraphLayoutEngine.LayoutResult;
import kairon.ui.swing.behaviorgraph.BehaviorGraphLayoutEngine.NodeLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.FontMetrics;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic left-to-right layered layout with a breadth-first root tree.
 */
public final class LayeredBehaviorGraphLayoutEngine
        implements BehaviorGraphLayoutEngine {

    public static final int NODE_RADIUS = 16;
    public static final int CURRENT_NODE_RADIUS = NODE_RADIUS + 4;
    public static final int HORIZONTAL_GAP = 140;
    public static final int VERTICAL_GAP = 64;
    public static final int CANVAS_MARGIN = 80;
    public static final int LABEL_GAP = 10;

    private static final int BARYCENTRIC_PASSES = 4;
    private static final int TRANSPOSE_PASSES = 4;
    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    LayeredBehaviorGraphLayoutEngine.class
            );

    @Override
    public LayoutResult layout(
            BehaviorGraphVisualizationSnapshot snapshot,
            FontMetrics fontMetrics
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(fontMetrics, "fontMetrics");

        NavigableMap<NormalizedEventType, NodeMetrics> nodes =
                collectNodes(snapshot.nodes(), fontMetrics);
        if (nodes.isEmpty()) {
            return new LayoutResult(
                    Map.of(),
                    CANVAS_MARGIN * 2,
                    CANVAS_MARGIN * 2
            );
        }

        Adjacency adjacency = adjacency(nodes.keySet(), snapshot.edges());
        NormalizedEventType root = selectRoot(nodes.keySet(), adjacency);
        Map<NormalizedEventType, Integer> levels = assignLevels(
                nodes.keySet(),
                adjacency.outgoing(),
                root
        );
        NavigableMap<Integer, List<NormalizedEventType>> layers =
                layers(levels);
        orderLayers(layers, adjacency);
        return placeNodes(nodes, layers);
    }

    private static NavigableMap<NormalizedEventType, NodeMetrics>
            collectNodes(
                    List<VisualizationNode> source,
                    FontMetrics fontMetrics
            ) {
        NavigableMap<NormalizedEventType, NodeMetrics> result =
                new TreeMap<>();
        for (VisualizationNode node : source) {
            String label = node.displayName()
                    + " ("
                    + node.activeEpisodeOccurrenceCount()
                    + ')';
            NodeMetrics metrics = new NodeMetrics(
                    label,
                    fontMetrics.stringWidth(label),
                    fontMetrics.getHeight()
            );
            NodeMetrics previous = result.putIfAbsent(
                    node.eventType(),
                    metrics
            );
            if (previous != null && !previous.equals(metrics)) {
                NodeMetrics selected = Comparator
                        .comparing(NodeMetrics::label)
                        .thenComparingInt(NodeMetrics::labelWidth)
                        .thenComparingInt(NodeMetrics::labelHeight)
                        .compare(previous, metrics) <= 0
                        ? previous
                        : metrics;
                result.put(node.eventType(), selected);
                LOGGER.warn(
                        "BEHAVIOR_GRAPH_LAYOUT_DUPLICATE_NODE eventType={}",
                        node.eventType()
                );
            }
        }
        return result;
    }

    private static Adjacency adjacency(
            Collection<NormalizedEventType> nodeTypes,
            List<VisualizationEdge> edges
    ) {
        NavigableMap<NormalizedEventType,
                NavigableSet<NormalizedEventType>> outgoing =
                emptyAdjacency(nodeTypes);
        NavigableMap<NormalizedEventType,
                NavigableSet<NormalizedEventType>> incoming =
                emptyAdjacency(nodeTypes);
        for (VisualizationEdge edge : edges) {
            if (!outgoing.containsKey(edge.from())
                    || !incoming.containsKey(edge.to())) {
                LOGGER.warn(
                        "BEHAVIOR_GRAPH_LAYOUT_DANGLING_EDGE from={} to={}",
                        edge.from(),
                        edge.to()
                );
                continue;
            }
            outgoing.get(edge.from()).add(edge.to());
            incoming.get(edge.to()).add(edge.from());
        }
        return new Adjacency(outgoing, incoming);
    }

    private static NavigableMap<NormalizedEventType,
            NavigableSet<NormalizedEventType>> emptyAdjacency(
                    Collection<NormalizedEventType> nodeTypes
            ) {
        NavigableMap<NormalizedEventType,
                NavigableSet<NormalizedEventType>> result =
                new TreeMap<>();
        for (NormalizedEventType eventType : nodeTypes) {
            result.put(eventType, new TreeSet<>());
        }
        return result;
    }

    private static NormalizedEventType selectRoot(
            Collection<NormalizedEventType> nodeTypes,
            Adjacency adjacency
    ) {
        if (nodeTypes.contains(NormalizedEventType.SYSTEM_ENTRY)) {
            return NormalizedEventType.SYSTEM_ENTRY;
        }

        Optional<NormalizedEventType> withoutIncoming = nodeTypes.stream()
                .filter(type -> adjacency.incoming()
                        .get(type)
                        .stream()
                        .noneMatch(parent -> !parent.equals(type)))
                .min(Comparator.naturalOrder());
        NormalizedEventType fallback = withoutIncoming.orElseGet(() ->
                nodeTypes.stream()
                        .min(Comparator.naturalOrder())
                        .orElseThrow()
        );
        LOGGER.warn(
                "BEHAVIOR_GRAPH_LAYOUT_SYSTEM_ENTRY_MISSING "
                        + "fallbackRoot={}",
                fallback
        );
        return fallback;
    }

    private static Map<NormalizedEventType, Integer> assignLevels(
            Collection<NormalizedEventType> nodeTypes,
            NavigableMap<NormalizedEventType,
                    NavigableSet<NormalizedEventType>> outgoing,
            NormalizedEventType root
    ) {
        Map<NormalizedEventType, Integer> levels = new TreeMap<>();
        Deque<NormalizedEventType> pending = new ArrayDeque<>();
        levels.put(root, 0);
        pending.addLast(root);

        while (!pending.isEmpty()) {
            NormalizedEventType current = pending.removeFirst();
            int nextLevel = Math.addExact(levels.get(current), 1);
            for (NormalizedEventType next : outgoing.get(current)) {
                if (next.equals(current) || levels.containsKey(next)) {
                    continue;
                }
                levels.put(next, nextLevel);
                pending.addLast(next);
            }
        }

        int maximumReachableLevel = levels.values()
                .stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        int fallbackLevel = Math.addExact(maximumReachableLevel, 1);
        for (NormalizedEventType eventType : nodeTypes) {
            levels.putIfAbsent(eventType, fallbackLevel);
        }
        return levels;
    }

    private static NavigableMap<Integer, List<NormalizedEventType>> layers(
            Map<NormalizedEventType, Integer> levels
    ) {
        NavigableMap<Integer, List<NormalizedEventType>> result =
                new TreeMap<>();
        levels.forEach((eventType, level) ->
                result.computeIfAbsent(
                        level,
                        ignored -> new ArrayList<>()
                ).add(eventType));
        result.values().forEach(layer ->
                layer.sort(Comparator.naturalOrder()));
        return result;
    }

    private static void orderLayers(
            NavigableMap<Integer, List<NormalizedEventType>> layers,
            Adjacency adjacency
    ) {
        for (int pass = 0; pass < BARYCENTRIC_PASSES; pass++) {
            Map<NormalizedEventType, Integer> ranks = ranks(layers);
            for (Integer level : layers.navigableKeySet()) {
                if (level.equals(layers.firstKey())) {
                    continue;
                }
                reorder(
                        layers.get(level),
                        adjacency.incoming(),
                        ranks,
                        true,
                        level,
                        layers
                );
                ranks = ranks(layers);
            }

            ranks = ranks(layers);
            for (Integer level : layers.descendingKeySet()) {
                if (level.equals(layers.lastKey())) {
                    continue;
                }
                reorder(
                        layers.get(level),
                        adjacency.outgoing(),
                        ranks,
                        false,
                        level,
                        layers
                );
                ranks = ranks(layers);
            }
            transposeLayers(layers, adjacency);
        }
    }

    private static void reorder(
            List<NormalizedEventType> layer,
            NavigableMap<NormalizedEventType,
                    NavigableSet<NormalizedEventType>> neighbors,
            Map<NormalizedEventType, Integer> ranks,
            boolean useEarlierLayers,
            int currentLevel,
            NavigableMap<Integer, List<NormalizedEventType>> layers
    ) {
        Map<NormalizedEventType, Integer> levelByType =
                levelByType(layers);
        layer.sort(Comparator
                .<NormalizedEventType>comparingDouble(
                        type -> barycenter(
                                neighbors.get(type),
                                ranks,
                                levelByType,
                                useEarlierLayers,
                                currentLevel,
                                ranks.getOrDefault(
                                        type,
                                        Integer.MAX_VALUE
                                )
                        )
                )
                .thenComparing(Comparator.naturalOrder()));
    }

    private static double barycenter(
            Collection<NormalizedEventType> neighbors,
            Map<NormalizedEventType, Integer> ranks,
            Map<NormalizedEventType, Integer> levels,
            boolean useEarlierLayers,
            int currentLevel,
            int fallbackRank
    ) {
        long sum = 0L;
        int count = 0;
        for (NormalizedEventType neighbor : neighbors) {
            Integer neighborLevel = levels.get(neighbor);
            Integer rank = ranks.get(neighbor);
            if (neighborLevel == null || rank == null) {
                continue;
            }
            boolean eligible = useEarlierLayers
                    ? neighborLevel < currentLevel
                    : neighborLevel > currentLevel;
            if (eligible) {
                sum += rank;
                count++;
            }
        }
        return count == 0
                ? fallbackRank
                : (double) sum / count;
    }

    private static void transposeLayers(
            NavigableMap<Integer, List<NormalizedEventType>> layers,
            Adjacency adjacency
    ) {
        for (int pass = 0; pass < TRANSPOSE_PASSES; pass++) {
            boolean changed = false;
            boolean forward = pass % 2 == 0;
            for (Integer level : layers.navigableKeySet()) {
                List<NormalizedEventType> layer = layers.get(level);
                if (layer.size() < 2) {
                    continue;
                }
                int index = forward ? 0 : layer.size() - 2;
                int limit = forward ? layer.size() - 1 : -1;
                int step = forward ? 1 : -1;
                while (index != limit) {
                    long before = affectedCrossings(
                            level,
                            layers,
                            adjacency
                    );
                    Collections.swap(layer, index, index + 1);
                    long after = affectedCrossings(
                            level,
                            layers,
                            adjacency
                    );
                    if (after < before) {
                        changed = true;
                    } else {
                        Collections.swap(layer, index, index + 1);
                    }
                    index += step;
                }
            }
            if (!changed) {
                return;
            }
        }
    }

    private static long affectedCrossings(
            int level,
            NavigableMap<Integer, List<NormalizedEventType>> layers,
            Adjacency adjacency
    ) {
        long result = 0L;
        Integer previous = layers.lowerKey(level);
        if (previous != null) {
            result += crossingsBetween(
                    layers.get(previous),
                    layers.get(level),
                    adjacency.outgoing()
            );
        }
        Integer next = layers.higherKey(level);
        if (next != null) {
            result += crossingsBetween(
                    layers.get(level),
                    layers.get(next),
                    adjacency.outgoing()
            );
        }
        return result;
    }

    private static long crossingsBetween(
            List<NormalizedEventType> sources,
            List<NormalizedEventType> targets,
            NavigableMap<NormalizedEventType,
                    NavigableSet<NormalizedEventType>> outgoing
    ) {
        Map<NormalizedEventType, Integer> targetRanks =
                new TreeMap<>();
        for (int index = 0; index < targets.size(); index++) {
            targetRanks.put(targets.get(index), index);
        }

        FenwickTree seenTargetRanks = new FenwickTree(targets.size());
        long seen = 0L;
        long crossings = 0L;
        for (NormalizedEventType source : sources) {
            List<Integer> ranks = outgoing.get(source).stream()
                    .filter(targetRanks::containsKey)
                    .map(targetRanks::get)
                    .sorted()
                    .toList();
            for (Integer rank : ranks) {
                crossings += seen
                        - seenTargetRanks.prefixCount(rank + 1);
            }
            for (Integer rank : ranks) {
                seenTargetRanks.add(rank + 1);
                seen++;
            }
        }
        return crossings;
    }

    private static Map<NormalizedEventType, Integer> ranks(
            NavigableMap<Integer, List<NormalizedEventType>> layers
    ) {
        Map<NormalizedEventType, Integer> result = new TreeMap<>();
        for (List<NormalizedEventType> layer : layers.values()) {
            for (int index = 0; index < layer.size(); index++) {
                result.put(layer.get(index), index);
            }
        }
        return result;
    }

    private static Map<NormalizedEventType, Integer> levelByType(
            NavigableMap<Integer, List<NormalizedEventType>> layers
    ) {
        Map<NormalizedEventType, Integer> result = new TreeMap<>();
        layers.forEach((level, types) -> types.forEach(type ->
                result.put(type, level)));
        return result;
    }

    private static LayoutResult placeNodes(
            NavigableMap<NormalizedEventType, NodeMetrics> metricsByType,
            NavigableMap<Integer, List<NormalizedEventType>> layers
    ) {
        Map<NormalizedEventType, NodeLayout> result =
                new LinkedHashMap<>();
        double columnLeft = CANVAS_MARGIN;
        double maximumRight = CANVAS_MARGIN;
        double maximumBottom = CANVAS_MARGIN;
        double rowHeight = Math.max(
                CURRENT_NODE_RADIUS * 2.0,
                metricsByType.values()
                        .stream()
                        .mapToInt(NodeMetrics::labelHeight)
                        .max()
                        .orElse(0)
        );

        for (Map.Entry<Integer, List<NormalizedEventType>> layer :
                layers.entrySet()) {
            double centerX = columnLeft + CURRENT_NODE_RADIUS;
            double columnLabelWidth = layer.getValue()
                    .stream()
                    .map(metricsByType::get)
                    .mapToInt(NodeMetrics::labelWidth)
                    .max()
                    .orElse(0);

            for (int row = 0; row < layer.getValue().size(); row++) {
                NormalizedEventType eventType = layer.getValue().get(row);
                NodeMetrics metrics = metricsByType.get(eventType);
                double centerY = CANVAS_MARGIN
                        + rowHeight / 2.0
                        + row * (rowHeight + VERTICAL_GAP);
                double labelX = centerX
                        + CURRENT_NODE_RADIUS
                        + LABEL_GAP;
                double labelY = centerY - metrics.labelHeight() / 2.0;
                NodeLayout layout = new NodeLayout(
                        layer.getKey(),
                        centerX,
                        centerY,
                        labelX,
                        labelY,
                        metrics.labelWidth(),
                        metrics.labelHeight()
                );
                result.put(eventType, layout);
                maximumRight = Math.max(
                        maximumRight,
                        Math.max(
                                centerX + CURRENT_NODE_RADIUS,
                                layout.labelRight()
                        )
                );
                maximumBottom = Math.max(
                        maximumBottom,
                        Math.max(
                                centerY + CURRENT_NODE_RADIUS,
                                layout.labelBottom()
                        )
                );
            }

            double columnWidth = CURRENT_NODE_RADIUS * 2.0
                    + LABEL_GAP
                    + columnLabelWidth;
            columnLeft += columnWidth + HORIZONTAL_GAP;
        }

        int canvasWidth = positiveCeiling(
                maximumRight + CANVAS_MARGIN
        );
        int canvasHeight = positiveCeiling(
                maximumBottom + CANVAS_MARGIN
        );
        return new LayoutResult(result, canvasWidth, canvasHeight);
    }

    private static int positiveCeiling(double value) {
        if (!Double.isFinite(value)
                || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "graph layout exceeds supported canvas dimensions"
            );
        }
        return Math.max(1, (int) Math.ceil(value));
    }

    private static final class FenwickTree {

        private final long[] tree;

        private FenwickTree(int size) {
            tree = new long[Math.max(1, size + 1)];
        }

        private void add(int index) {
            for (int current = index;
                    current < tree.length;
                    current += current & -current) {
                tree[current]++;
            }
        }

        private long prefixCount(int index) {
            long result = 0L;
            for (int current = Math.min(index, tree.length - 1);
                    current > 0;
                    current -= current & -current) {
                result += tree[current];
            }
            return result;
        }
    }

    private record NodeMetrics(
            String label,
            int labelWidth,
            int labelHeight
    ) {
    }

    private record Adjacency(
            NavigableMap<NormalizedEventType,
                    NavigableSet<NormalizedEventType>> outgoing,
            NavigableMap<NormalizedEventType,
                    NavigableSet<NormalizedEventType>> incoming
    ) {
    }
}
