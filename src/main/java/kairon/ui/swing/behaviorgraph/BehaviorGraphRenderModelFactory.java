package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot.VisualizationEdge;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot.VisualizationNode;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.ui.swing.behaviorgraph.BehaviorGraphLayoutEngine.LayoutResult;
import kairon.ui.swing.behaviorgraph.BehaviorGraphLayoutEngine.NodeLayout;
import kairon.ui.swing.behaviorgraph.BehaviorGraphEdgeRouter.Geometry;
import kairon.ui.swing.behaviorgraph.BehaviorGraphEdgeRouter.RouteKey;
import kairon.ui.swing.behaviorgraph.BehaviorGraphEdgeRouter.RouteRequest;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.EdgeRenderData;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.EdgeRenderKind;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.NodeRenderData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.FontMetrics;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Converts immutable aggregate snapshots into cached painting geometry.
 */
final class BehaviorGraphRenderModelFactory {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(BehaviorGraphRenderModelFactory.class);

    private final BehaviorGraphLayoutEngine layoutEngine;
    private final EdgeIntensityScale intensityScale;
    private final BehaviorGraphEdgeRouter edgeRouter;

    BehaviorGraphRenderModelFactory(
            BehaviorGraphLayoutEngine layoutEngine,
            EdgeIntensityScale intensityScale
    ) {
        this.layoutEngine = Objects.requireNonNull(
                layoutEngine,
                "layoutEngine"
        );
        this.intensityScale = Objects.requireNonNull(
                intensityScale,
                "intensityScale"
        );
        edgeRouter = new BehaviorGraphEdgeRouter();
    }

    BehaviorGraphRenderModel create(
            BehaviorGraphVisualizationSnapshot snapshot,
            FontMetrics normalMetrics,
            FontMetrics boldMetrics,
            BehaviorGraphRenderModel previous,
            boolean fullRelayout
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(normalMetrics, "normalMetrics");
        Objects.requireNonNull(boldMetrics, "boldMetrics");

        Map<NormalizedEventType, VisualizationNode> snapshotNodes =
                sortedNodes(snapshot.nodes());
        boolean positionsReusable = !fullRelayout
                && previous != null
                && previous.nodes().keySet().equals(snapshotNodes.keySet());
        LayoutResult layout = positionsReusable
                ? reuseLayout(previous)
                : layoutEngine.layout(snapshot, normalMetrics);

        Optional<NormalizedEventType> current = snapshot.currentEventType();
        if (current.filter(type -> !snapshotNodes.containsKey(type))
                .isPresent()) {
            LOGGER.warn(
                    "BEHAVIOR_GRAPH_CURSOR_NODE_MISSING graphId={} "
                            + "eventType={}",
                    snapshot.graphId().canonicalValue(),
                    current.orElseThrow()
            );
            current = Optional.empty();
        }

        Map<NormalizedEventType, NodeRenderData> nodes =
                buildNodes(
                        snapshotNodes,
                        layout,
                        current,
                        normalMetrics,
                        boldMetrics
                );
        Map<VisualizationEdge, Double> intensities =
                intensityScale.scale(snapshot.edges());
        List<EdgeRenderData> edges = buildEdges(
                snapshot,
                nodes,
                previous,
                positionsReusable,
                intensities
        );

        int canvasWidth = layout.canvasWidth();
        int canvasHeight = layout.canvasHeight();
        if (positionsReusable && previous != null) {
            canvasWidth = Math.max(canvasWidth, previous.canvasWidth());
            canvasHeight = Math.max(canvasHeight, previous.canvasHeight());
        }
        for (NodeRenderData node : nodes.values()) {
            canvasWidth = Math.max(
                    canvasWidth,
                    ceiling(node.labelBounds().getMaxX()
                            + LayeredBehaviorGraphLayoutEngine.CANVAS_MARGIN)
            );
            canvasHeight = Math.max(
                    canvasHeight,
                    ceiling(Math.max(
                            node.labelBounds().getMaxY(),
                            node.circleBounds().getMaxY()
                    ) + LayeredBehaviorGraphLayoutEngine.CANVAS_MARGIN)
            );
        }
        for (EdgeRenderData edge : edges) {
            Rectangle2D bounds = edge.path().getBounds2D();
            canvasWidth = Math.max(
                    canvasWidth,
                    ceiling(bounds.getMaxX()
                            + LayeredBehaviorGraphLayoutEngine.CANVAS_MARGIN)
            );
            canvasHeight = Math.max(
                    canvasHeight,
                    ceiling(bounds.getMaxY()
                            + LayeredBehaviorGraphLayoutEngine.CANVAS_MARGIN)
            );
        }

        return new BehaviorGraphRenderModel(
                Math.max(1, canvasWidth),
                Math.max(1, canvasHeight),
                nodes,
                edges,
                current,
                snapshot.graphVersion(),
                snapshot.topologyVersion()
        );
    }

    private static Map<NormalizedEventType, VisualizationNode> sortedNodes(
            List<VisualizationNode> nodes
    ) {
        Map<NormalizedEventType, VisualizationNode> result = new TreeMap<>();
        for (VisualizationNode node : nodes) {
            VisualizationNode duplicate = result.put(
                    node.eventType(),
                    node
            );
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "snapshot contains duplicate visualization nodes"
                );
            }
        }
        return result;
    }

    private static LayoutResult reuseLayout(
            BehaviorGraphRenderModel previous
    ) {
        Map<NormalizedEventType, NodeLayout> layouts =
                new LinkedHashMap<>();
        previous.nodes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    NodeRenderData node = entry.getValue();
                    layouts.put(
                            entry.getKey(),
                            new NodeLayout(
                                    node.level(),
                                    node.centerX(),
                                    node.centerY(),
                                    node.labelBounds().x,
                                    node.labelBounds().y,
                                    node.labelBounds().width,
                                    node.labelBounds().height
                            )
                    );
                });
        return new LayoutResult(
                layouts,
                previous.canvasWidth(),
                previous.canvasHeight()
        );
    }

    private static Map<NormalizedEventType, NodeRenderData> buildNodes(
            Map<NormalizedEventType, VisualizationNode> snapshotNodes,
            LayoutResult layout,
            Optional<NormalizedEventType> current,
            FontMetrics normalMetrics,
            FontMetrics boldMetrics
    ) {
        Map<NormalizedEventType, NodeRenderData> nodes =
                new LinkedHashMap<>();
        snapshotNodes.forEach((eventType, node) -> {
            NodeLayout placement = layout.nodes().get(eventType);
            if (placement == null) {
                throw new IllegalStateException(
                        "layout omitted node " + eventType
                );
            }
            boolean isCurrent = current.filter(eventType::equals).isPresent();
            FontMetrics metrics = isCurrent ? boldMetrics : normalMetrics;
            String label = label(node);
            double radius = isCurrent
                    ? LayeredBehaviorGraphLayoutEngine.CURRENT_NODE_RADIUS
                    : LayeredBehaviorGraphLayoutEngine.NODE_RADIUS;
            double labelX = placement.centerX()
                    + LayeredBehaviorGraphLayoutEngine.CURRENT_NODE_RADIUS
                    + LayeredBehaviorGraphLayoutEngine.LABEL_GAP;
            double labelY = placement.centerY()
                    - metrics.getHeight() / 2.0;
            nodes.put(eventType, new NodeRenderData(
                    eventType,
                    label,
                    node.activeEpisodeOccurrenceCount(),
                    placement.centerX(),
                    placement.centerY(),
                    placement.level(),
                    new Ellipse2D.Double(
                            placement.centerX() - radius,
                            placement.centerY() - radius,
                            radius * 2.0,
                            radius * 2.0
                    ),
                    new Rectangle2D.Double(
                            labelX,
                            labelY,
                            metrics.stringWidth(label),
                            metrics.getHeight()
                    ),
                    isCurrent,
                    NodeModelReach.of(eventType)
            ));
        });
        return nodes;
    }

    private List<EdgeRenderData> buildEdges(
            BehaviorGraphVisualizationSnapshot snapshot,
            Map<NormalizedEventType, NodeRenderData> nodes,
            BehaviorGraphRenderModel previous,
            boolean geometryReusable,
            Map<VisualizationEdge, Double> intensities
    ) {
        Map<RouteKey, EdgeRenderData> previousEdges =
                previousEdges(previous);
        List<VisualizationEdge> sortedEdges = snapshot.edges().stream()
                .sorted(Comparator
                        .comparing(VisualizationEdge::from)
                        .thenComparing(VisualizationEdge::to))
                .toList();
        List<PreparedEdge> prepared = new ArrayList<>();
        for (VisualizationEdge edge : sortedEdges) {
            NodeRenderData from = nodes.get(edge.from());
            NodeRenderData to = nodes.get(edge.to());
            if (from == null || to == null) {
                LOGGER.warn(
                        "BEHAVIOR_GRAPH_EDGE_ENDPOINT_MISSING "
                                + "graphId={} from={} to={}",
                        snapshot.graphId().canonicalValue(),
                        edge.from(),
                        edge.to()
                );
                continue;
            }
            EdgeRenderKind kind = kind(from, to);
            prepared.add(new PreparedEdge(
                    edge,
                    new RouteRequest(
                            edge.from(),
                            edge.to(),
                            from,
                            to,
                            kind
                    )
            ));
        }

        boolean routesReusable = geometryReusable
                && previous != null
                && routingObstaclesEqual(previous.nodes(), nodes)
                && prepared.stream().allMatch(edge -> {
                    EdgeRenderData prior = previousEdges.get(
                            edge.request().key()
                    );
                    return prior != null
                            && prior.kind() == edge.request().kind();
                });
        Map<RouteKey, Geometry> routes = routesReusable
                ? Map.of()
                : edgeRouter.route(
                        prepared.stream()
                                .map(PreparedEdge::request)
                                .toList(),
                        nodes
                );

        List<EdgeRenderData> result = new ArrayList<>(prepared.size());
        for (PreparedEdge edge : prepared) {
            RouteKey key = edge.request().key();
            EdgeRenderData prior = previousEdges.get(key);
            Geometry geometry = routesReusable
                    ? new Geometry(prior.path(), prior.arrowHead())
                    : Objects.requireNonNull(
                            routes.get(key),
                            "edge router omitted " + key
                    );
            VisualizationEdge source = edge.edge();
            result.add(new EdgeRenderData(
                    source.from(),
                    source.to(),
                    geometry.path(),
                    geometry.arrowHead(),
                    source.effectiveWeight(),
                    intensities.getOrDefault(
                            source,
                            intensityScale.minimumIntensity()
                    ),
                    edge.request().kind()
            ));
        }
        return List.copyOf(result);
    }

    private static Map<RouteKey, EdgeRenderData> previousEdges(
            BehaviorGraphRenderModel previous
    ) {
        if (previous == null) {
            return Map.of();
        }
        Map<RouteKey, EdgeRenderData> result = new LinkedHashMap<>();
        for (EdgeRenderData edge : previous.edges()) {
            result.put(new RouteKey(edge.from(), edge.to()), edge);
        }
        return result;
    }

    private static boolean routingObstaclesEqual(
            Map<NormalizedEventType, NodeRenderData> previous,
            Map<NormalizedEventType, NodeRenderData> current
    ) {
        if (!previous.keySet().equals(current.keySet())) {
            return false;
        }
        for (NormalizedEventType eventType : previous.keySet()) {
            NodeRenderData before = previous.get(eventType);
            NodeRenderData after = current.get(eventType);
            if (before.level() != after.level()
                    || !rectangleEquals(
                            before.circleBounds().getBounds2D(),
                            after.circleBounds().getBounds2D()
                    )
                    || !rectangleEquals(
                            before.labelBounds(),
                            after.labelBounds()
                    )) {
                return false;
            }
        }
        return true;
    }

    private static boolean rectangleEquals(
            Rectangle2D first,
            Rectangle2D second
    ) {
        return Double.compare(first.getX(), second.getX()) == 0
                && Double.compare(first.getY(), second.getY()) == 0
                && Double.compare(
                        first.getWidth(),
                        second.getWidth()
                ) == 0
                && Double.compare(
                        first.getHeight(),
                        second.getHeight()
                ) == 0;
    }

    private static EdgeRenderKind kind(
            NodeRenderData from,
            NodeRenderData to
    ) {
        if (from.eventType().equals(to.eventType())) {
            return EdgeRenderKind.SELF;
        }
        if (to.level() > from.level()) {
            return EdgeRenderKind.FORWARD;
        }
        return to.level() == from.level()
                ? EdgeRenderKind.SAME_LEVEL
                : EdgeRenderKind.BACKWARD;
    }

    private static String label(VisualizationNode node) {
        return node.displayName()
                + " ("
                + node.activeEpisodeOccurrenceCount()
                + ')';
    }

    private static int ceiling(double value) {
        return (int) Math.min(
                Integer.MAX_VALUE,
                Math.max(1.0, Math.ceil(value))
        );
    }

    private record PreparedEdge(
            VisualizationEdge edge,
            RouteRequest request
    ) {
    }
}
