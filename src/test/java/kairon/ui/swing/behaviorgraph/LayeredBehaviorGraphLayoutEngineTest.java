package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationEdge;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationNode;
import kairon.behavior.model.GraphId;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.ui.swing.behaviorgraph.BehaviorGraphLayoutEngine.LayoutResult;
import kairon.ui.swing.behaviorgraph.BehaviorGraphLayoutEngine.NodeLayout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LayeredBehaviorGraphLayoutEngineTest {

    private static final GraphId GRAPH_ID =
            new GraphId("F123456", 42L);
    private static final LayeredBehaviorGraphLayoutEngine ENGINE =
            new LayeredBehaviorGraphLayoutEngine();

    private static Graphics2D metricsGraphics;
    private static FontMetrics fontMetrics;

    @BeforeAll
    static void createFontMetrics() {
        BufferedImage image = new BufferedImage(
                16,
                16,
                BufferedImage.TYPE_INT_ARGB
        );
        metricsGraphics = image.createGraphics();
        metricsGraphics.setFont(new Font(
                Font.DIALOG,
                Font.PLAIN,
                12
        ));
        fontMetrics = metricsGraphics.getFontMetrics();
    }

    @AfterAll
    static void disposeFontMetrics() {
        metricsGraphics.dispose();
    }

    @Test
    void systemEntryHasMinimumXAndReachableNodesAreToItsRight() {
        LayoutResult layout = layout(
                List.of(
                        node("B"),
                        node("SYSTEM_ENTRY"),
                        node("A"),
                        node("C")
                ),
                List.of(
                        edge("SYSTEM_ENTRY", "A"),
                        edge("SYSTEM_ENTRY", "B"),
                        edge("A", "C")
                )
        );

        NodeLayout root = nodeLayout(layout, "SYSTEM_ENTRY");
        double minimumX = layout.nodes()
                .values()
                .stream()
                .mapToDouble(NodeLayout::centerX)
                .min()
                .orElseThrow();

        assertEquals(minimumX, root.centerX());
        assertEquals(0, root.level());
        assertTrue(nodeLayout(layout, "A").centerX() > root.centerX());
        assertTrue(nodeLayout(layout, "B").centerX() > root.centerX());
        assertTrue(nodeLayout(layout, "C").centerX() > root.centerX());
    }

    @Test
    void levelsUseMinimumDirectedDistanceFromSystemEntry() {
        LayoutResult layout = layout(
                nodes("SYSTEM_ENTRY", "A", "B", "C"),
                List.of(
                        edge("SYSTEM_ENTRY", "A"),
                        edge("A", "B"),
                        edge("SYSTEM_ENTRY", "B"),
                        edge("A", "C"),
                        edge("B", "C")
                )
        );

        assertEquals(0, nodeLayout(layout, "SYSTEM_ENTRY").level());
        assertEquals(1, nodeLayout(layout, "A").level());
        assertEquals(1, nodeLayout(layout, "B").level());
        assertEquals(2, nodeLayout(layout, "C").level());
    }

    @Test
    void selfEdgeDoesNotCauseInfiniteTraversal() {
        LayoutResult layout = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> layout(
                        nodes("SYSTEM_ENTRY", "A"),
                        List.of(
                                edge("SYSTEM_ENTRY", "SYSTEM_ENTRY"),
                                edge("SYSTEM_ENTRY", "A"),
                                edge("A", "A")
                        )
                )
        );

        assertEquals(0, nodeLayout(layout, "SYSTEM_ENTRY").level());
        assertEquals(1, nodeLayout(layout, "A").level());
    }

    @Test
    void cycleAndBackwardEdgeDoNotMoveSystemEntry() {
        LayoutResult layout = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> layout(
                        nodes("SYSTEM_ENTRY", "A", "B", "C"),
                        List.of(
                                edge("SYSTEM_ENTRY", "A"),
                                edge("A", "B"),
                                edge("B", "C"),
                                edge("C", "A"),
                                edge("C", "SYSTEM_ENTRY")
                        )
                )
        );

        assertEquals(0, nodeLayout(layout, "SYSTEM_ENTRY").level());
        assertEquals(1, nodeLayout(layout, "A").level());
        assertEquals(2, nodeLayout(layout, "B").level());
        assertEquals(3, nodeLayout(layout, "C").level());
    }

    @Test
    void unreachableNodesUseFallbackColumnToTheRight() {
        LayoutResult layout = layout(
                nodes("SYSTEM_ENTRY", "A", "UNREACHABLE_A", "UNREACHABLE_B"),
                List.of(
                        edge("SYSTEM_ENTRY", "A"),
                        edge("UNREACHABLE_A", "UNREACHABLE_B"),
                        edge("UNREACHABLE_B", "UNREACHABLE_A")
                )
        );

        NodeLayout reachable = nodeLayout(layout, "A");
        NodeLayout first = nodeLayout(layout, "UNREACHABLE_A");
        NodeLayout second = nodeLayout(layout, "UNREACHABLE_B");
        assertEquals(2, first.level());
        assertEquals(2, second.level());
        assertTrue(first.centerX() > reachable.centerX());
        assertEquals(first.centerX(), second.centerX());
    }

    @Test
    void sameSnapshotAlwaysProducesIdenticalCoordinates() {
        BehaviorGraphVisualizationSnapshot snapshot = snapshot(
                1L,
                1L,
                nodes("SYSTEM_ENTRY", "A", "B", "C"),
                List.of(
                        edge("SYSTEM_ENTRY", "A"),
                        edge("SYSTEM_ENTRY", "B"),
                        edge("A", "C")
                )
        );

        LayoutResult first = ENGINE.layout(snapshot, fontMetrics);
        LayoutResult second = ENGINE.layout(snapshot, fontMetrics);

        assertEquals(first, second);
    }

    @Test
    void inputIterationOrderDoesNotAffectLayout() {
        List<VisualizationNode> orderedNodes =
                nodes("SYSTEM_ENTRY", "A", "B", "C", "D");
        List<VisualizationEdge> orderedEdges = List.of(
                edge("SYSTEM_ENTRY", "A"),
                edge("SYSTEM_ENTRY", "B"),
                edge("A", "C"),
                edge("B", "D"),
                edge("C", "D")
        );
        List<VisualizationNode> reversedNodes =
                new ArrayList<>(orderedNodes);
        List<VisualizationEdge> reversedEdges =
                new ArrayList<>(orderedEdges);
        Collections.reverse(reversedNodes);
        Collections.reverse(reversedEdges);

        LayoutResult ordered = ENGINE.layout(
                snapshot(1L, 1L, orderedNodes, orderedEdges),
                fontMetrics
        );
        LayoutResult reversed = ENGINE.layout(
                snapshot(1L, 1L, reversedNodes, reversedEdges),
                fontMetrics
        );

        assertEquals(ordered, reversed);
    }

    @Test
    void barycentricOrderingUsesParentsWithEventTypeTieBreakers() {
        LayoutResult layout = layout(
                nodes(
                        "SYSTEM_ENTRY",
                        "A_PARENT",
                        "B_PARENT",
                        "A_CHILD",
                        "Z_CHILD"
                ),
                List.of(
                        edge("SYSTEM_ENTRY", "A_PARENT"),
                        edge("SYSTEM_ENTRY", "B_PARENT"),
                        edge("A_PARENT", "Z_CHILD"),
                        edge("B_PARENT", "A_CHILD")
                )
        );

        assertTrue(
                nodeLayout(layout, "A_PARENT").centerY()
                        < nodeLayout(layout, "B_PARENT").centerY()
        );
        assertTrue(
                nodeLayout(layout, "Z_CHILD").centerY()
                        < nodeLayout(layout, "A_CHILD").centerY()
        );
    }

    @Test
    void nodeAndLabelBoundsFitInsideCanvasWithMargins() {
        LayoutResult layout = layout(
                List.of(
                        node("SYSTEM_ENTRY", "System Entry", 999L),
                        node(
                                "VERY_LONG_EVENT",
                                "An Extremely Long Behavior Event Display Name",
                                123_456_789L
                        ),
                        node("SECOND_EVENT", "Second Event", 2L)
                ),
                List.of(
                        edge("SYSTEM_ENTRY", "VERY_LONG_EVENT"),
                        edge("SYSTEM_ENTRY", "SECOND_EVENT")
                )
        );

        for (NodeLayout node : layout.nodes().values()) {
            assertTrue(
                    node.centerX()
                            - LayeredBehaviorGraphLayoutEngine
                            .CURRENT_NODE_RADIUS
                            >= 0.0
            );
            assertTrue(
                    node.centerY()
                            - LayeredBehaviorGraphLayoutEngine
                            .CURRENT_NODE_RADIUS
                            >= 0.0
            );
            assertTrue(
                    node.centerX()
                            + LayeredBehaviorGraphLayoutEngine
                            .CURRENT_NODE_RADIUS
                            <= layout.canvasWidth()
            );
            assertTrue(
                    node.centerY()
                            + LayeredBehaviorGraphLayoutEngine
                            .CURRENT_NODE_RADIUS
                            <= layout.canvasHeight()
            );
            assertTrue(node.labelX() >= 0.0);
            assertTrue(node.labelY() >= 0.0);
            assertTrue(node.labelRight() <= layout.canvasWidth());
            assertTrue(node.labelBottom() <= layout.canvasHeight());
        }

        double rightmost = layout.nodes()
                .values()
                .stream()
                .mapToDouble(node -> Math.max(
                        node.labelRight(),
                        node.centerX()
                                + LayeredBehaviorGraphLayoutEngine
                                .CURRENT_NODE_RADIUS
                ))
                .max()
                .orElseThrow();
        double bottommost = layout.nodes()
                .values()
                .stream()
                .mapToDouble(node -> Math.max(
                        node.labelBottom(),
                        node.centerY()
                                + LayeredBehaviorGraphLayoutEngine
                                .CURRENT_NODE_RADIUS
                ))
                .max()
                .orElseThrow();
        assertTrue(
                layout.canvasWidth() - rightmost
                        >= LayeredBehaviorGraphLayoutEngine.CANVAS_MARGIN
        );
        assertTrue(
                layout.canvasHeight() - bottommost
                        >= LayeredBehaviorGraphLayoutEngine.CANVAS_MARGIN
        );
    }

    @Test
    void missingSystemEntryUsesLexicographicallyFirstRootWithoutIncomingEdges() {
        LayoutResult layout = layout(
                nodes("B", "A", "C"),
                List.of(
                        edge("A", "C"),
                        edge("B", "C")
                )
        );

        assertEquals(0, nodeLayout(layout, "A").level());
        assertEquals(
                layout.nodes()
                        .values()
                        .stream()
                        .mapToDouble(NodeLayout::centerX)
                        .min()
                        .orElseThrow(),
                nodeLayout(layout, "A").centerX()
        );
        assertTrue(
                nodeLayout(layout, "B").centerX()
                        > nodeLayout(layout, "A").centerX()
        );
    }

    @Test
    void missingSystemEntryCycleUsesLexicographicallyFirstNode() {
        LayoutResult layout = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> layout(
                        nodes("C", "B"),
                        List.of(
                                edge("B", "C"),
                                edge("C", "B")
                        )
                )
        );

        assertEquals(0, nodeLayout(layout, "B").level());
        assertEquals(1, nodeLayout(layout, "C").level());
    }

    @Test
    void occurrenceCountChangesDoNotReorderNodes() {
        List<VisualizationEdge> edges = List.of(
                edge("SYSTEM_ENTRY", "A"),
                edge("SYSTEM_ENTRY", "B"),
                edge("A", "C"),
                edge("B", "D")
        );
        LayoutResult initial = ENGINE.layout(
                snapshot(
                        10L,
                        4L,
                        nodes("SYSTEM_ENTRY", "A", "B", "C", "D"),
                        edges
                ),
                fontMetrics
        );
        LayoutResult updated = ENGINE.layout(
                snapshot(
                        11L,
                        4L,
                        List.of(
                                node(
                                        "SYSTEM_ENTRY",
                                        "System Entry",
                                        Long.MAX_VALUE
                                ),
                                node("A", "A", 999_999L),
                                node("B", "B", 2L),
                                node("C", "C", 5L),
                                node("D", "D", 7L)
                        ),
                        edges
                ),
                fontMetrics
        );

        for (NormalizedEventType eventType : initial.nodes().keySet()) {
            NodeLayout before = initial.nodes().get(eventType);
            NodeLayout after = updated.nodes().get(eventType);
            assertEquals(before.level(), after.level());
            assertEquals(before.centerY(), after.centerY());
        }
    }

    @Test
    void topologyChangeCanProduceDifferentLayerAssignment() {
        List<VisualizationNode> nodes =
                nodes("SYSTEM_ENTRY", "A", "B");
        LayoutResult before = ENGINE.layout(
                snapshot(
                        2L,
                        2L,
                        nodes,
                        List.of(
                                edge("SYSTEM_ENTRY", "A"),
                                edge("A", "B")
                        )
                ),
                fontMetrics
        );
        LayoutResult after = ENGINE.layout(
                snapshot(
                        3L,
                        3L,
                        nodes,
                        List.of(
                                edge("SYSTEM_ENTRY", "A"),
                                edge("A", "B"),
                                edge("SYSTEM_ENTRY", "B")
                        )
                ),
                fontMetrics
        );

        assertEquals(2, nodeLayout(before, "B").level());
        assertEquals(1, nodeLayout(after, "B").level());
        assertNotEquals(
                nodeLayout(before, "B").centerX(),
                nodeLayout(after, "B").centerX()
        );
    }

    @Test
    void emptySnapshotProducesPositiveEmptyCanvas() {
        LayoutResult layout = layout(List.of(), List.of());

        assertTrue(layout.nodes().isEmpty());
        assertEquals(
                LayeredBehaviorGraphLayoutEngine.CANVAS_MARGIN * 2,
                layout.canvasWidth()
        );
        assertEquals(
                LayeredBehaviorGraphLayoutEngine.CANVAS_MARGIN * 2,
                layout.canvasHeight()
        );
    }

    private static LayoutResult layout(
            List<VisualizationNode> nodes,
            List<VisualizationEdge> edges
    ) {
        return ENGINE.layout(
                snapshot(1L, 1L, nodes, edges),
                fontMetrics
        );
    }

    private static BehaviorGraphVisualizationSnapshot snapshot(
            long graphVersion,
            long topologyVersion,
            List<VisualizationNode> nodes,
            List<VisualizationEdge> edges
    ) {
        return new BehaviorGraphVisualizationSnapshot(
                GRAPH_ID,
                "Test Ship",
                graphVersion,
                topologyVersion,
                Instant.parse("2026-07-30T06:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                nodes,
                edges
        );
    }

    private static List<VisualizationNode> nodes(String... eventTypes) {
        List<VisualizationNode> result =
                new ArrayList<>(eventTypes.length);
        for (String eventType : eventTypes) {
            result.add(node(eventType));
        }
        return List.copyOf(result);
    }

    private static VisualizationNode node(String eventType) {
        return node(
                eventType,
                displayName(eventType),
                1L
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

    private static VisualizationEdge edge(String from, String to) {
        return new VisualizationEdge(
                type(from),
                type(to),
                1L,
                1.0
        );
    }

    private static NodeLayout nodeLayout(
            LayoutResult layout,
            String eventType
    ) {
        return layout.node(type(eventType)).orElseThrow();
    }

    private static NormalizedEventType type(String value) {
        return NormalizedEventType.of(value);
    }

    private static String displayName(String eventType) {
        String lower = eventType.toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0))
                + lower.substring(1).replace('_', ' ');
    }
}
