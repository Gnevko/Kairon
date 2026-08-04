package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationEdge;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationNode;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.NodeRenderData;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BehaviorGraphCanvasTest {

    private static final GraphId GRAPH_ID =
            new GraphId("F123456", 42L);

    @Test
    void rendersOnEdtUpdatesPreferredSizeAndHitTestsNodes()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BehaviorGraphCanvas canvas = new BehaviorGraphCanvas();
            assertEquals(
                    "No behavior graph data for the active ship yet.",
                    BehaviorGraphCanvas.EMPTY_MESSAGE
            );
            assertEquals(
                    "No active ship is available.",
                    BehaviorGraphCanvas.NO_ACTIVE_SHIP_MESSAGE
            );
            assertEquals(
                    "Unable to refresh the behavior graph.",
                    BehaviorGraphCanvas.REFRESH_ERROR_MESSAGE
            );
            assertFalse(canvas.hasGraphData());
            assertTrue(canvas.currentNodeCenter().isEmpty());
            assertEquals(new Dimension(1, 1), canvas.getPreferredSize());

            BehaviorGraphVisualizationSnapshot initial = snapshot(
                    10,
                    7,
                    List.of(
                            node("SYSTEM_ENTRY", "System Entry", 1),
                            node("A", "A", 2),
                            node("B", "B", 3)
                    ),
                    List.of(
                            edge("SYSTEM_ENTRY", "A", 1.0),
                            edge("A", "B", 2.0)
                    ),
                    Optional.of(type("B"))
            );
            assertTrue(canvas.setSnapshot(initial, false));
            assertTrue(canvas.hasGraphData());
            BehaviorGraphRenderModel initialModel = canvas.renderModel();
            assertNotEquals(new Dimension(1, 1), canvas.getPreferredSize());
            assertEquals(
                    new Dimension(
                            initialModel.canvasWidth(),
                            initialModel.canvasHeight()
                    ),
                    canvas.getPreferredSize()
            );

            NodeRenderData current = initialModel.nodes().get(type("B"));
            Point centerPoint = new Point(
                    (int) Math.round(current.centerX()),
                    (int) Math.round(current.centerY())
            );
            assertTrue(canvas.isNodeAt(centerPoint));
            assertEquals(
                    Optional.of(type("B")),
                    canvas.findNodeAt(centerPoint)
            );
            Point labelPoint = new Point(
                    (int) Math.ceil(current.labelBounds().getCenterX()),
                    (int) Math.ceil(current.labelBounds().getCenterY())
            );
            assertEquals(
                    Optional.of(type("B")),
                    canvas.findNodeAt(labelPoint)
            );
            assertFalse(canvas.isNodeAt(new Point(0, 0)));
            Point2D.Double currentCenter =
                    canvas.currentNodeCenter().orElseThrow();
            assertEquals(current.centerX(), currentCenter.x);
            assertEquals(current.centerY(), currentCenter.y);
            canvas.setSelectedNode(Optional.of(type("A")));
            assertEquals(Optional.of(type("A")), canvas.selectedNode());
            paint(canvas);

            BehaviorGraphVisualizationSnapshot weightOnly = snapshot(
                    11,
                    7,
                    List.of(
                            node("SYSTEM_ENTRY", "System Entry", 2),
                            node("A", "A", 5),
                            node("B", "B", 8)
                    ),
                    List.of(
                            edge("SYSTEM_ENTRY", "A", 20.0),
                            edge("A", "B", 40.0)
                    ),
                    Optional.of(type("B"))
            );
            assertFalse(canvas.setSnapshot(weightOnly, false));
            BehaviorGraphRenderModel weightModel = canvas.renderModel();
            assertSameCenter(initialModel, weightModel, type("SYSTEM_ENTRY"));
            assertSameCenter(initialModel, weightModel, type("A"));
            assertSameCenter(initialModel, weightModel, type("B"));
            assertEquals("B (8)", weightModel.nodes().get(type("B")).label());
            assertEquals(Optional.of(type("A")), canvas.selectedNode());

            BehaviorGraphVisualizationSnapshot topologyChanged = snapshot(
                    12,
                    8,
                    weightOnly.nodes(),
                    List.of(
                            edge("SYSTEM_ENTRY", "A", 20.0),
                            edge("A", "B", 40.0),
                            edge("SYSTEM_ENTRY", "B", 1.0)
                    ),
                    Optional.of(type("B"))
            );
            double previousBX =
                    weightModel.nodes().get(type("B")).centerX();
            assertTrue(canvas.setSnapshot(topologyChanged, false));
            assertNotEquals(
                    previousBX,
                    canvas.renderModel().nodes().get(type("B")).centerX()
            );

            BehaviorGraphRenderModel validGraph = canvas.renderModel();
            canvas.showRefreshError();
            assertTrue(canvas.hasGraphData());
            assertEquals(validGraph, canvas.renderModel());
            paint(canvas);

            canvas.showEmptyGraph();
            assertFalse(canvas.hasGraphData());
            assertTrue(canvas.selectedNode().isEmpty());
            assertEquals(new Dimension(1, 1), canvas.getPreferredSize());
            canvas.setSize(420, 260);
            paint(canvas);

            canvas.showNoActiveShip();
            assertFalse(canvas.hasGraphData());
            assertEquals(new Dimension(1, 1), canvas.getPreferredSize());
            paint(canvas);
        });
    }

    @Test
    void overlappingHitBoundsUseDocumentedDeterministicPriority() {
        NormalizedEventType alpha = type("ALPHA");
        NormalizedEventType beta = type("BETA");
        Point overlap = new Point(50, 50);

        NodeRenderData alphaNode = renderNode(
                alpha,
                false,
                new Ellipse2D.Double(10, 10, 20, 20),
                new Rectangle2D.Double(40, 40, 30, 20)
        );
        NodeRenderData currentBeta = renderNode(
                beta,
                true,
                new Ellipse2D.Double(40, 40, 20, 20),
                new Rectangle2D.Double(40, 40, 30, 20)
        );
        BehaviorGraphRenderModel currentModel = renderModel(
                alphaNode,
                currentBeta
        );

        assertEquals(
                Optional.of(beta),
                BehaviorGraphCanvas.findNodeAt(
                        currentModel,
                        Optional.of(alpha),
                        overlap
                )
        );

        NodeRenderData ordinaryBeta = renderNode(
                beta,
                false,
                new Ellipse2D.Double(40, 40, 20, 20),
                new Rectangle2D.Double(40, 40, 30, 20)
        );
        BehaviorGraphRenderModel selectedModel = renderModel(
                alphaNode,
                ordinaryBeta
        );
        assertEquals(
                Optional.of(alpha),
                BehaviorGraphCanvas.findNodeAt(
                        selectedModel,
                        Optional.of(alpha),
                        overlap
                )
        );
        assertEquals(
                Optional.of(beta),
                BehaviorGraphCanvas.findNodeAt(
                        selectedModel,
                        Optional.empty(),
                        overlap
                )
        );

        BehaviorGraphRenderModel tieModel = renderModel(
                renderNode(
                        alpha,
                        false,
                        new Ellipse2D.Double(40, 40, 20, 20),
                        new Rectangle2D.Double(40, 40, 30, 20)
                ),
                ordinaryBeta
        );
        assertEquals(
                Optional.of(alpha),
                BehaviorGraphCanvas.findNodeAt(
                        tieModel,
                        Optional.empty(),
                        overlap
                )
        );
        assertEquals(
                tieModel,
                tieModel,
                "hit testing must not mutate the render model"
        );
    }

    @Test
    void emptySnapshotKeepsAValidScrollableCanvasAndPaintsOnEdt()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BehaviorGraphCanvas canvas = new BehaviorGraphCanvas();
            assertTrue(canvas.setSnapshot(
                    snapshot(
                            1,
                            1,
                            List.of(),
                            List.of(),
                            Optional.empty()
                    ),
                    true
            ));

            assertFalse(canvas.hasGraphData());
            assertTrue(canvas.getPreferredSize().width > 0);
            assertTrue(canvas.getPreferredSize().height > 0);
            assertEquals(
                    canvas.getPreferredSize(),
                    canvas.getPreferredScrollableViewportSize()
            );
            paint(canvas);
        });
    }

    private static void assertSameCenter(
            BehaviorGraphRenderModel expected,
            BehaviorGraphRenderModel actual,
            NormalizedEventType eventType
    ) {
        assertEquals(
                expected.nodes().get(eventType).centerX(),
                actual.nodes().get(eventType).centerX()
        );
        assertEquals(
                expected.nodes().get(eventType).centerY(),
                actual.nodes().get(eventType).centerY()
        );
    }

    private static void paint(BehaviorGraphCanvas canvas) {
        Dimension preferred = canvas.getPreferredSize();
        int width = Math.max(420, preferred.width);
        int height = Math.max(260, preferred.height);
        canvas.setSize(width, height);
        BufferedImage image = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = image.createGraphics();
        try {
            canvas.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }

    private static NodeRenderData renderNode(
            NormalizedEventType eventType,
            boolean current,
            Ellipse2D.Double circle,
            Rectangle2D.Double label
    ) {
        return new NodeRenderData(
                eventType,
                eventType.value(),
                0L,
                circle.getCenterX(),
                circle.getCenterY(),
                0,
                circle,
                label,
                current
        );
    }

    private static BehaviorGraphRenderModel renderModel(
            NodeRenderData... nodes
    ) {
        Map<NormalizedEventType, NodeRenderData> byType =
                new LinkedHashMap<>();
        Optional<NormalizedEventType> current = Optional.empty();
        for (NodeRenderData node : nodes) {
            byType.put(node.eventType(), node);
            if (node.current()) {
                current = Optional.of(node.eventType());
            }
        }
        return new BehaviorGraphRenderModel(
                200,
                120,
                byType,
                List.of(),
                current,
                1L,
                1L
        );
    }

    private static BehaviorGraphVisualizationSnapshot snapshot(
            long graphVersion,
            long topologyVersion,
            List<VisualizationNode> nodes,
            List<VisualizationEdge> edges,
            Optional<NormalizedEventType> current
    ) {
        return new BehaviorGraphVisualizationSnapshot(
                GRAPH_ID,
                "Test Ship",
                graphVersion,
                topologyVersion,
                Instant.parse("2026-07-30T07:00:00Z"),
                current,
                current.map(ignored ->
                        new EventOccurrenceId("occurrence-1")),
                current.map(ignored ->
                        new SystemEpisodeId("episode-1")),
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
            String to,
            double effectiveWeight
    ) {
        return new VisualizationEdge(
                type(from),
                type(to),
                1,
                effectiveWeight
        );
    }

    private static NormalizedEventType type(String value) {
        return NormalizedEventType.of(value);
    }
}
