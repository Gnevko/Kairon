package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationEdge;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationNode;
import kairon.behavior.model.GraphId;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.EdgeRenderData;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.NodeRenderData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

final class BehaviorGraphEdgeRouterTest {

    private static final GraphId GRAPH_ID =
            new GraphId("F12345678", 9L);

    private static Graphics2D metricsGraphics;
    private static FontMetrics normalMetrics;
    private static FontMetrics boldMetrics;

    @BeforeAll
    static void createFontMetrics() {
        BufferedImage image = new BufferedImage(
                16,
                16,
                BufferedImage.TYPE_INT_ARGB
        );
        metricsGraphics = image.createGraphics();
        Font normal = new Font(Font.DIALOG, Font.PLAIN, 12);
        normalMetrics = metricsGraphics.getFontMetrics(normal);
        boldMetrics = metricsGraphics.getFontMetrics(
                normal.deriveFont(Font.BOLD)
        );
    }

    @AfterAll
    static void disposeFontMetrics() {
        metricsGraphics.dispose();
    }

    @Test
    void screenshotTopologyRoutesNeverIntersectNodeLabels() {
        BehaviorGraphRenderModel model = create(
                screenshotSnapshot(1L, screenshotEdges())
        );

        for (EdgeRenderData edge : model.edges()) {
            for (NodeRenderData node : model.nodes().values()) {
                assertFalse(
                        intersects(edge.path(), node.labelBounds()),
                        () -> edge.from()
                                + " -> "
                                + edge.to()
                                + " intersects label "
                                + node.label()
                );
            }
        }
    }

    @Test
    void screenshotBackwardRouteAvoidsUnrelatedForwardRoute() {
        BehaviorGraphRenderModel model = create(
                screenshotSnapshot(1L, screenshotEdges())
        );
        EdgeRenderData backward = edge(
                model,
                "FSD_TARGET_SELECTED",
                "SUPERCRUISE_JUMP_STARTED"
        );
        EdgeRenderData forward = edge(
                model,
                "SUPERCRUISE_ENTRY",
                "LEAVE_BODY"
        );

        assertFalse(
                properlyIntersects(backward.path(), forward.path())
        );
    }

    @Test
    void screenshotVerticalChannelsRemainVisuallySeparated() {
        BehaviorGraphRenderModel model = create(
                screenshotSnapshot(1L, screenshotEdges())
        );

        for (int firstIndex = 0;
                firstIndex < model.edges().size();
                firstIndex++) {
            EdgeRenderData first = model.edges().get(firstIndex);
            for (int secondIndex = firstIndex + 1;
                    secondIndex < model.edges().size();
                    secondIndex++) {
                EdgeRenderData second = model.edges().get(secondIndex);
                assertFalse(
                        verticalChannelsOverlap(
                                first.path(),
                                second.path()
                        ),
                        () -> first.from()
                                + " -> "
                                + first.to()
                                + " shares a vertical channel with "
                                + second.from()
                                + " -> "
                                + second.to()
                );
            }
        }
    }

    @Test
    void reversingInputCollectionsDoesNotChangeRoutes() {
        List<VisualizationEdge> reversedEdges =
                new ArrayList<>(screenshotEdges());
        Collections.reverse(reversedEdges);
        BehaviorGraphRenderModel ordered = create(
                screenshotSnapshot(1L, screenshotEdges())
        );
        BehaviorGraphRenderModel reversed = create(
                screenshotSnapshot(1L, reversedEdges)
        );

        assertEquals(routeSignatures(ordered), routeSignatures(reversed));
    }

    @Test
    void widerCountLabelReroutesEdgesWithoutMovingNodes() {
        BehaviorGraphRenderModelFactory factory = factory();
        BehaviorGraphRenderModel initial = factory.create(
                snapshot(
                        1L,
                        List.of(
                                node(
                                        "SYSTEM_ENTRY",
                                        "System Entry",
                                        9L
                                ),
                                node("A", "A", 1L)
                        ),
                        List.of(edge("SYSTEM_ENTRY", "A"))
                ),
                normalMetrics,
                boldMetrics,
                null,
                true
        );
        BehaviorGraphRenderModel updated = factory.create(
                snapshot(
                        2L,
                        List.of(
                                node(
                                        "SYSTEM_ENTRY",
                                        "System Entry",
                                        1_000_000L
                                ),
                                node("A", "A", 1L)
                        ),
                        List.of(edge("SYSTEM_ENTRY", "A"))
                ),
                normalMetrics,
                boldMetrics,
                initial,
                false
        );

        for (NormalizedEventType eventType : initial.nodes().keySet()) {
            assertEquals(
                    initial.nodes().get(eventType).centerX(),
                    updated.nodes().get(eventType).centerX()
            );
            assertEquals(
                    initial.nodes().get(eventType).centerY(),
                    updated.nodes().get(eventType).centerY()
            );
        }
        assertNotEquals(
                pathSignature(initial.edges().getFirst().path()),
                pathSignature(updated.edges().getFirst().path())
        );
        assertFalse(
                intersects(
                        updated.edges().getFirst().path(),
                        updated.nodes()
                                .get(type("SYSTEM_ENTRY"))
                                .labelBounds()
                )
        );
    }

    @Test
    void routesThreeHundredNodesAndTwoThousandEdgesResponsively() {
        BehaviorGraphRenderModel model = assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> create(largeSnapshot())
        );

        assertEquals(300, model.nodes().size());
        assertEquals(2_000, model.edges().size());
    }

    private static BehaviorGraphRenderModel create(
            BehaviorGraphVisualizationSnapshot snapshot
    ) {
        return factory().create(
                snapshot,
                normalMetrics,
                boldMetrics,
                null,
                true
        );
    }

    private static BehaviorGraphRenderModelFactory factory() {
        return new BehaviorGraphRenderModelFactory(
                new LayeredBehaviorGraphLayoutEngine(),
                new EdgeIntensityScale()
        );
    }

    private static BehaviorGraphVisualizationSnapshot screenshotSnapshot(
            long graphVersion,
            List<VisualizationEdge> edges
    ) {
        return snapshot(
                graphVersion,
                List.of(
                        node("SYSTEM_ENTRY", "System Entry", 1L),
                        node(
                                "SUPERCRUISE_JUMP_STARTED",
                                "Supercruise Jump Started",
                                2L
                        ),
                        node(
                                "SUPERCRUISE_ENTRY",
                                "Supercruise Entry",
                                2L
                        ),
                        node(
                                "FSD_TARGET_SELECTED",
                                "FSD Target Selected",
                                2L
                        ),
                        node(
                                "SAA_SIGNALS_FOUND",
                                "SAA Signals Found",
                                1L
                        ),
                        node("LEAVE_BODY", "Leave Body", 1L)
                ),
                edges
        );
    }

    private static BehaviorGraphVisualizationSnapshot largeSnapshot() {
        List<VisualizationNode> nodes = new ArrayList<>();
        nodes.add(node("SYSTEM_ENTRY", "System Entry", 1L));
        for (int index = 1; index < 300; index++) {
            String name = numberedType(index);
            nodes.add(node(name, name, 1L));
        }

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        List<VisualizationEdge> edges = new ArrayList<>();
        addUniqueEdge(
                edges,
                keys,
                "SYSTEM_ENTRY",
                numberedType(1)
        );
        for (int index = 1; index < 299; index++) {
            addUniqueEdge(
                    edges,
                    keys,
                    numberedType(index),
                    numberedType(index + 1)
            );
        }
        for (int offset = 1; edges.size() < 2_000; offset++) {
            for (int from = 1;
                    from < 300 && edges.size() < 2_000;
                    from++) {
                int to = 1 + Math.floorMod(
                        from * 37 + offset * 53,
                        299
                );
                if (from != to) {
                    addUniqueEdge(
                            edges,
                            keys,
                            numberedType(from),
                            numberedType(to)
                    );
                }
            }
        }
        return snapshot(1L, nodes, edges);
    }

    private static void addUniqueEdge(
            List<VisualizationEdge> edges,
            LinkedHashSet<String> keys,
            String from,
            String to
    ) {
        String key = from + "->" + to;
        if (keys.add(key)) {
            edges.add(edge(from, to));
        }
    }

    private static String numberedType(int index) {
        return "EVENT_" + String.format(
                java.util.Locale.ROOT,
                "%03d",
                index
        );
    }

    private static List<VisualizationEdge> screenshotEdges() {
        return List.of(
                edge(
                        "SYSTEM_ENTRY",
                        "SUPERCRUISE_JUMP_STARTED"
                ),
                edge(
                        "SUPERCRUISE_JUMP_STARTED",
                        "SUPERCRUISE_ENTRY"
                ),
                edge(
                        "SUPERCRUISE_JUMP_STARTED",
                        "FSD_TARGET_SELECTED"
                ),
                edge(
                        "FSD_TARGET_SELECTED",
                        "SUPERCRUISE_ENTRY"
                ),
                edge(
                        "FSD_TARGET_SELECTED",
                        "SUPERCRUISE_JUMP_STARTED"
                ),
                edge(
                        "SUPERCRUISE_ENTRY",
                        "SAA_SIGNALS_FOUND"
                ),
                edge(
                        "SUPERCRUISE_ENTRY",
                        "LEAVE_BODY"
                ),
                edge(
                        "SAA_SIGNALS_FOUND",
                        "FSD_TARGET_SELECTED"
                )
        );
    }

    private static BehaviorGraphVisualizationSnapshot snapshot(
            long graphVersion,
            List<VisualizationNode> nodes,
            List<VisualizationEdge> edges
    ) {
        return new BehaviorGraphVisualizationSnapshot(
                GRAPH_ID,
                "Test Ship",
                graphVersion,
                1L,
                Instant.parse("2026-07-30T07:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                nodes,
                edges
        );
    }

    private static VisualizationNode node(
            String eventType,
            String displayName,
            long occurrenceCount
    ) {
        return new VisualizationNode(
                type(eventType),
                displayName,
                occurrenceCount
        );
    }

    private static VisualizationEdge edge(
            String from,
            String to
    ) {
        return new VisualizationEdge(
                type(from),
                type(to),
                1L,
                1.0
        );
    }

    private static EdgeRenderData edge(
            BehaviorGraphRenderModel model,
            String from,
            String to
    ) {
        return model.edges().stream()
                .filter(edge -> edge.from().equals(type(from))
                        && edge.to().equals(type(to)))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, List<Long>> routeSignatures(
            BehaviorGraphRenderModel model
    ) {
        Map<String, List<Long>> result = new LinkedHashMap<>();
        for (EdgeRenderData edge : model.edges()) {
            result.put(
                    edge.from().value() + "->" + edge.to().value(),
                    pathSignature(edge.path())
            );
        }
        return result;
    }

    private static List<Long> pathSignature(Path2D.Double path) {
        List<Long> result = new ArrayList<>();
        PathIterator iterator = path.getPathIterator(null);
        double[] coordinates = new double[6];
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coordinates);
            result.add((long) type);
            int coordinateCount = switch (type) {
                case PathIterator.SEG_MOVETO,
                        PathIterator.SEG_LINETO -> 2;
                case PathIterator.SEG_QUADTO -> 4;
                case PathIterator.SEG_CUBICTO -> 6;
                default -> 0;
            };
            for (int index = 0; index < coordinateCount; index++) {
                result.add(Double.doubleToLongBits(coordinates[index]));
            }
            iterator.next();
        }
        return List.copyOf(result);
    }

    private static boolean intersects(
            Path2D.Double path,
            Rectangle2D rectangle
    ) {
        return segments(path).stream()
                .anyMatch(rectangle::intersectsLine);
    }

    private static boolean properlyIntersects(
            Path2D.Double first,
            Path2D.Double second
    ) {
        for (Line2D.Double firstSegment : segments(first)) {
            for (Line2D.Double secondSegment : segments(second)) {
                if (properlyIntersects(
                        firstSegment,
                        secondSegment
                )) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean properlyIntersects(
            Line2D.Double first,
            Line2D.Double second
    ) {
        double firstDx = first.x2 - first.x1;
        double firstDy = first.y2 - first.y1;
        double secondDx = second.x2 - second.x1;
        double secondDy = second.y2 - second.y1;
        double denominator = firstDx * secondDy
                - firstDy * secondDx;
        if (Math.abs(denominator) < 0.000_001) {
            return false;
        }
        double offsetX = second.x1 - first.x1;
        double offsetY = second.y1 - first.y1;
        double firstRatio = (
                offsetX * secondDy - offsetY * secondDx
        ) / denominator;
        double secondRatio = (
                offsetX * firstDy - offsetY * firstDx
        ) / denominator;
        return firstRatio > 0.000_001
                && firstRatio < 0.999_999
                && secondRatio > 0.000_001
                && secondRatio < 0.999_999;
    }

    private static boolean verticalChannelsOverlap(
            Path2D.Double first,
            Path2D.Double second
    ) {
        for (Line2D.Double firstSegment : segments(first)) {
            if (!isVerticalChannel(firstSegment)) {
                continue;
            }
            for (Line2D.Double secondSegment : segments(second)) {
                if (!isVerticalChannel(secondSegment)) {
                    continue;
                }
                double horizontalDistance = Math.abs(
                        firstSegment.x1 - secondSegment.x1
                );
                double overlap = Math.min(
                        Math.max(firstSegment.y1, firstSegment.y2),
                        Math.max(secondSegment.y1, secondSegment.y2)
                ) - Math.max(
                        Math.min(firstSegment.y1, firstSegment.y2),
                        Math.min(secondSegment.y1, secondSegment.y2)
                );
                if (horizontalDistance < 8.0 && overlap > 8.0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isVerticalChannel(Line2D.Double segment) {
        return Math.abs(segment.x1 - segment.x2) < 0.000_001
                && Math.abs(segment.y1 - segment.y2) > 8.0;
    }

    private static List<Line2D.Double> segments(Path2D.Double path) {
        FlatteningPathIterator iterator = new FlatteningPathIterator(
                path.getPathIterator(null),
                0.5
        );
        List<Line2D.Double> result = new ArrayList<>();
        double[] coordinates = new double[6];
        double previousX = 0.0;
        double previousY = 0.0;
        while (!iterator.isDone()) {
            int segment = iterator.currentSegment(coordinates);
            if (segment == PathIterator.SEG_MOVETO) {
                previousX = coordinates[0];
                previousY = coordinates[1];
            } else if (segment == PathIterator.SEG_LINETO) {
                result.add(new Line2D.Double(
                        previousX,
                        previousY,
                        coordinates[0],
                        coordinates[1]
                ));
                previousX = coordinates[0];
                previousY = coordinates[1];
            }
            iterator.next();
        }
        return List.copyOf(result);
    }

    private static NormalizedEventType type(String value) {
        return NormalizedEventType.of(value);
    }
}
