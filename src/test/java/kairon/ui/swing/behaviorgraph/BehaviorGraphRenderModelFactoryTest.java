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
import kairon.ui.swing.behaviorgraph.BehaviorGraphLayoutEngine.LayoutResult;
import kairon.ui.swing.behaviorgraph.BehaviorGraphLayoutEngine.NodeLayout;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.EdgeRenderData;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.EdgeRenderKind;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.NodeRenderData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BehaviorGraphRenderModelFactoryTest {

    private static final GraphId GRAPH_ID =
            new GraphId("F123456", 42L);
    private static final Instant EVALUATION_TIME =
            Instant.parse("2026-07-30T07:00:00Z");

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
    void labelsIncludeDisplayNameAndActiveEpisodeCountIncludingZero() {
        BehaviorGraphRenderModel model = factory().create(
                snapshot(
                        8,
                        5,
                        List.of(
                                node("SYSTEM_ENTRY", "System Entry", 0),
                                node("TOUCHDOWN", "Touchdown", 55)
                        ),
                        List.of(edge(
                                "SYSTEM_ENTRY",
                                "TOUCHDOWN",
                                4.0
                        )),
                        Optional.of(type("TOUCHDOWN"))
                ),
                normalMetrics,
                boldMetrics,
                null,
                true
        );

        NodeRenderData root = model.nodes().get(type("SYSTEM_ENTRY"));
        NodeRenderData current = model.nodes().get(type("TOUCHDOWN"));
        assertEquals("System Entry (0)", root.label());
        assertEquals(0, root.activeEpisodeOccurrenceCount());
        assertEquals("Touchdown (55)", current.label());
        assertEquals(55, current.activeEpisodeOccurrenceCount());
        assertFalse(root.current());
        assertTrue(current.current());
        assertEquals(
                LayeredBehaviorGraphLayoutEngine.NODE_RADIUS * 2.0,
                root.circleBounds().width
        );
        assertEquals(
                LayeredBehaviorGraphLayoutEngine.CURRENT_NODE_RADIUS * 2.0,
                current.circleBounds().width
        );
        assertEquals(8, model.graphVersion());
        assertEquals(5, model.topologyVersion());
        assertTrue(
                current.labelBounds().getMaxX()
                        <= model.canvasWidth()
        );
        assertTrue(
                current.labelBounds().getMaxY()
                        <= model.canvasHeight()
        );
    }

    @Test
    void weightOnlyUpdateReusesCoordinatesButTopologyUpdateRelayouts() {
        CountingLayoutEngine layoutEngine = new CountingLayoutEngine();
        BehaviorGraphRenderModelFactory factory =
                new BehaviorGraphRenderModelFactory(
                        layoutEngine,
                        new EdgeIntensityScale()
                );
        List<VisualizationNode> nodes = List.of(
                node("SYSTEM_ENTRY", "System Entry", 1),
                node("A", "A", 1)
        );
        BehaviorGraphRenderModel initial = factory.create(
                snapshot(
                        1,
                        1,
                        nodes,
                        List.of(edge("SYSTEM_ENTRY", "A", 1.0)),
                        Optional.empty()
                ),
                normalMetrics,
                boldMetrics,
                null,
                true
        );
        assertEquals(1, layoutEngine.invocationCount());

        BehaviorGraphRenderModel weightOnly = factory.create(
                snapshot(
                        2,
                        1,
                        List.of(
                                node("SYSTEM_ENTRY", "System Entry", 2),
                                node("A", "A", 7)
                        ),
                        List.of(edge("SYSTEM_ENTRY", "A", 20.0)),
                        Optional.empty()
                ),
                normalMetrics,
                boldMetrics,
                initial,
                false
        );

        assertEquals(1, layoutEngine.invocationCount());
        assertSameCoordinates(initial, weightOnly);
        assertEquals(20.0, weightOnly.edges().getFirst().effectiveWeight());
        assertEquals("A (7)", weightOnly.nodes().get(type("A")).label());

        BehaviorGraphRenderModel topologyChanged = factory.create(
                snapshot(
                        3,
                        2,
                        nodes,
                        List.of(
                                edge("SYSTEM_ENTRY", "A", 20.0),
                                edge("A", "A", 1.0)
                        ),
                        Optional.empty()
                ),
                normalMetrics,
                boldMetrics,
                weightOnly,
                true
        );

        assertEquals(2, layoutEngine.invocationCount());
        assertNotEquals(
                weightOnly.nodes().get(type("A")).centerX(),
                topologyChanged.nodes().get(type("A")).centerX()
        );
    }

    @Test
    void buildsForwardSameLevelBackwardAndSelfEdgeGeometry() {
        BehaviorGraphRenderModel model = factory().create(
                snapshot(
                        1,
                        1,
                        List.of(
                                node("SYSTEM_ENTRY", "System Entry", 1),
                                node("A", "A", 1),
                                node("B", "B", 1)
                        ),
                        List.of(
                                edge("SYSTEM_ENTRY", "A", 1.0),
                                edge("SYSTEM_ENTRY", "B", 1.0),
                                edge("A", "B", 1.0),
                                edge("A", "SYSTEM_ENTRY", 1.0),
                                edge("A", "A", 1.0)
                        ),
                        Optional.empty()
                ),
                normalMetrics,
                boldMetrics,
                null,
                true
        );
        Map<String, EdgeRenderData> byEndpoints = new LinkedHashMap<>();
        for (EdgeRenderData edge : model.edges()) {
            byEndpoints.put(key(edge.from(), edge.to()), edge);
            assertNotNull(edge.path().getCurrentPoint());
            assertTrue(
                    segmentCount(edge.path().getPathIterator(null)) >= 2
            );
            assertTrue(
                    segmentCount(
                            edge.arrowHead().getPathIterator(null)
                    ) >= 4
            );
            assertFalse(edge.arrowHead().getBounds2D().isEmpty());
        }

        assertEquals(
                EdgeRenderKind.FORWARD,
                byEndpoints.get("SYSTEM_ENTRY->A").kind()
        );
        assertEquals(
                EdgeRenderKind.SAME_LEVEL,
                byEndpoints.get("A->B").kind()
        );
        assertEquals(
                EdgeRenderKind.BACKWARD,
                byEndpoints.get("A->SYSTEM_ENTRY").kind()
        );
        assertEquals(
                EdgeRenderKind.SELF,
                byEndpoints.get("A->A").kind()
        );
    }

    @Test
    void missingCursorNodeIsIgnoredWithoutFailingRenderModelCreation() {
        BehaviorGraphRenderModel model = factory().create(
                snapshot(
                        1,
                        1,
                        List.of(node(
                                "SYSTEM_ENTRY",
                                "System Entry",
                                1
                        )),
                        List.of(),
                        Optional.of(type("MISSING_NODE"))
                ),
                normalMetrics,
                boldMetrics,
                null,
                true
        );

        assertTrue(model.currentNode().isEmpty());
        assertFalse(
                model.nodes().get(type("SYSTEM_ENTRY")).current()
        );
    }

    private static BehaviorGraphRenderModelFactory factory() {
        return new BehaviorGraphRenderModelFactory(
                new LayeredBehaviorGraphLayoutEngine(),
                new EdgeIntensityScale()
        );
    }

    private static void assertSameCoordinates(
            BehaviorGraphRenderModel expected,
            BehaviorGraphRenderModel actual
    ) {
        assertEquals(expected.nodes().keySet(), actual.nodes().keySet());
        for (NormalizedEventType eventType : expected.nodes().keySet()) {
            NodeRenderData before = expected.nodes().get(eventType);
            NodeRenderData after = actual.nodes().get(eventType);
            assertEquals(before.centerX(), after.centerX());
            assertEquals(before.centerY(), after.centerY());
            assertEquals(before.level(), after.level());
        }
    }

    private static int segmentCount(PathIterator iterator) {
        int count = 0;
        double[] coordinates = new double[6];
        while (!iterator.isDone()) {
            iterator.currentSegment(coordinates);
            count++;
            iterator.next();
        }
        return count;
    }

    private static BehaviorGraphVisualizationSnapshot snapshot(
            long graphVersion,
            long topologyVersion,
            List<VisualizationNode> nodes,
            List<VisualizationEdge> edges,
            Optional<NormalizedEventType> current
    ) {
        Optional<EventOccurrenceId> occurrenceId = current.isPresent()
                ? Optional.of(new EventOccurrenceId("occurrence-1"))
                : Optional.empty();
        Optional<SystemEpisodeId> episodeId = current.isPresent()
                ? Optional.of(new SystemEpisodeId("episode-1"))
                : Optional.empty();
        return new BehaviorGraphVisualizationSnapshot(
                GRAPH_ID,
                "Test Ship",
                graphVersion,
                topologyVersion,
                EVALUATION_TIME,
                current,
                occurrenceId,
                episodeId,
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

    private static String key(
            NormalizedEventType from,
            NormalizedEventType to
    ) {
        return from.value() + "->" + to.value();
    }

    private static NormalizedEventType type(String value) {
        return NormalizedEventType.of(value);
    }

    private static final class CountingLayoutEngine
            implements BehaviorGraphLayoutEngine {

        private int invocationCount;

        @Override
        public LayoutResult layout(
                BehaviorGraphVisualizationSnapshot snapshot,
                FontMetrics fontMetrics
        ) {
            invocationCount++;
            int invocationOffset = invocationCount * 40;
            Map<NormalizedEventType, NodeLayout> layouts =
                    new LinkedHashMap<>();
            List<VisualizationNode> sorted =
                    new ArrayList<>(snapshot.nodes());
            sorted.sort(java.util.Comparator.comparing(
                    VisualizationNode::eventType
            ));
            for (int index = 0; index < sorted.size(); index++) {
                VisualizationNode node = sorted.get(index);
                double centerX = 100.0
                        + invocationOffset
                        + index * 180.0;
                double centerY = 100.0 + index * 70.0;
                String label = node.displayName()
                        + " ("
                        + node.activeEpisodeOccurrenceCount()
                        + ')';
                layouts.put(node.eventType(), new NodeLayout(
                        index,
                        centerX,
                        centerY,
                        centerX + 30.0,
                        centerY - fontMetrics.getHeight() / 2.0,
                        fontMetrics.stringWidth(label),
                        fontMetrics.getHeight()
                ));
            }
            return new LayoutResult(layouts, 900, 500);
        }

        private int invocationCount() {
            return invocationCount;
        }
    }
}
