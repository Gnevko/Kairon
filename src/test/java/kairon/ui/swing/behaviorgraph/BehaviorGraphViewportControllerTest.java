package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationNode;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BehaviorGraphViewportControllerTest {

    private static final Dimension VIEWPORT = new Dimension(200, 160);
    private static final Dimension CANVAS = new Dimension(1_000, 800);

    @Test
    void currentNodeIsCenteredInViewport() {
        assertEquals(
                new Point(400, 320),
                BehaviorGraphViewportController.centeredViewportPosition(
                        new Point2D.Double(500, 400),
                        VIEWPORT,
                        CANVAS
                )
        );
    }

    @Test
    void centeredCoordinateNeverBecomesNegative() {
        Point result =
                BehaviorGraphViewportController.centeredViewportPosition(
                        new Point2D.Double(-500, -500),
                        VIEWPORT,
                        CANVAS
                );

        assertTrue(result.x >= 0);
        assertTrue(result.y >= 0);
    }

    @Test
    void centeredCoordinateNeverExceedsCanvasBoundary() {
        Point result =
                BehaviorGraphViewportController.centeredViewportPosition(
                        new Point2D.Double(5_000, 5_000),
                        VIEWPORT,
                        CANVAS
                );

        assertEquals(new Point(800, 640), result);
    }

    @Test
    void canvasSmallerThanViewportUsesOrigin() {
        assertEquals(
                new Point(0, 0),
                BehaviorGraphViewportController.centeredViewportPosition(
                        new Point2D.Double(50, 40),
                        new Dimension(500, 400),
                        new Dimension(100, 80)
                )
        );
    }

    @Test
    void missingCurrentNodeProducesNoViewportCoordinate() {
        Optional<Point> result =
                BehaviorGraphViewportController.centeredViewportPosition(
                        Optional.<Point2D.Double>empty(),
                        VIEWPORT,
                        CANVAS
                );

        assertTrue(result.isEmpty());
    }

    @Test
    void nodeNearTopLeftIsClampedToOrigin() {
        assertEquals(
                new Point(0, 0),
                BehaviorGraphViewportController.centeredViewportPosition(
                        new Point2D.Double(10, 15),
                        VIEWPORT,
                        CANVAS
                )
        );
    }

    @Test
    void nodeNearBottomRightIsClampedToMaximumPosition() {
        assertEquals(
                new Point(800, 640),
                BehaviorGraphViewportController.centeredViewportPosition(
                        new Point2D.Double(990, 790),
                        VIEWPORT,
                        CANVAS
                )
        );
    }

    @Test
    void panningIsClampedToValidViewportBounds() {
        assertEquals(
                new Point(0, 0),
                BehaviorGraphViewportController.panViewportPosition(
                        new Point(50, 40),
                        new Point(100, 100),
                        new Point(500, 500),
                        VIEWPORT,
                        CANVAS
                )
        );
        assertEquals(
                new Point(800, 640),
                BehaviorGraphViewportController.panViewportPosition(
                        new Point(50, 40),
                        new Point(100, 100),
                        new Point(-5_000, -5_000),
                        VIEWPORT,
                        CANVAS
                )
        );
    }

    @Test
    void clickSelectsWhileNodeDragDoesNotAndPanningStillWorks()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BehaviorGraphCanvas canvas = new BehaviorGraphCanvas();
            canvas.setSnapshot(snapshot(), true);
            canvas.setSize(canvas.getPreferredSize());
            JScrollPane scrollPane = new JScrollPane(canvas);
            scrollPane.setSize(240, 190);
            scrollPane.doLayout();
            scrollPane.getViewport().setViewSize(
                    canvas.getPreferredSize()
            );
            List<NormalizedEventType> selections = new ArrayList<>();
            BehaviorGraphViewportController controller =
                    new BehaviorGraphViewportController(
                            scrollPane,
                            canvas,
                            selections::add
                    );
            try {
                Point node = canvas.renderModel().nodes()
                        .get(NormalizedEventType.SYSTEM_ENTRY)
                        .circleBounds()
                        .getBounds()
                        .getLocation();
                node.translate(10, 10);
                double originalX = canvas.renderModel().nodes()
                        .get(NormalizedEventType.SYSTEM_ENTRY)
                        .centerX();
                double originalY = canvas.renderModel().nodes()
                        .get(NormalizedEventType.SYSTEM_ENTRY)
                        .centerY();

                dispatch(canvas, MouseEvent.MOUSE_PRESSED, node,
                        500, 500, MouseEvent.BUTTON1, 0);
                dispatch(canvas, MouseEvent.MOUSE_RELEASED, node,
                        500, 500, MouseEvent.BUTTON1, 0);
                assertEquals(
                        List.of(NormalizedEventType.SYSTEM_ENTRY),
                        selections
                );

                selections.clear();
                dispatch(canvas, MouseEvent.MOUSE_PRESSED, node,
                        500, 500, MouseEvent.BUTTON1, 0);
                dispatch(canvas, MouseEvent.MOUSE_DRAGGED, node,
                        510, 500, MouseEvent.NOBUTTON,
                        InputEvent.BUTTON1_DOWN_MASK);
                dispatch(canvas, MouseEvent.MOUSE_RELEASED, node,
                        500, 500, MouseEvent.BUTTON1, 0);
                assertTrue(selections.isEmpty());
                assertEquals(originalX, canvas.renderModel().nodes()
                        .get(NormalizedEventType.SYSTEM_ENTRY)
                        .centerX());
                assertEquals(originalY, canvas.renderModel().nodes()
                        .get(NormalizedEventType.SYSTEM_ENTRY)
                        .centerY());

                scrollPane.getViewport().setViewPosition(
                        new Point(80, 60)
                );
                Point background = new Point(2, 2);
                assertFalse(canvas.isNodeAt(background));
                Point expectedPan =
                        BehaviorGraphViewportController
                                .panViewportPosition(
                                        new Point(80, 60),
                                        new Point(500, 500),
                                        new Point(470, 480),
                                        scrollPane.getViewport()
                                                .getExtentSize(),
                                        scrollPane.getViewport()
                                                .getViewSize()
                                );
                dispatch(canvas, MouseEvent.MOUSE_PRESSED, background,
                        500, 500, MouseEvent.BUTTON1, 0);
                dispatch(canvas, MouseEvent.MOUSE_DRAGGED, background,
                        470, 480, MouseEvent.NOBUTTON,
                        InputEvent.BUTTON1_DOWN_MASK);
                dispatch(canvas, MouseEvent.MOUSE_RELEASED, background,
                        470, 480, MouseEvent.BUTTON1, 0);
                assertEquals(
                        expectedPan,
                        scrollPane.getViewport().getViewPosition()
                );

                scrollPane.getViewport().setViewPosition(
                        new Point(80, 60)
                );
                dispatch(canvas, MouseEvent.MOUSE_PRESSED, node,
                        500, 500, MouseEvent.BUTTON2, 0);
                dispatch(canvas, MouseEvent.MOUSE_DRAGGED, node,
                        470, 480, MouseEvent.NOBUTTON,
                        InputEvent.BUTTON2_DOWN_MASK);
                dispatch(canvas, MouseEvent.MOUSE_RELEASED, node,
                        470, 480, MouseEvent.BUTTON2, 0);
                assertEquals(
                        expectedPan,
                        scrollPane.getViewport().getViewPosition()
                );
            } finally {
                controller.dispose();
            }
        });
    }

    @Test
    void dragThresholdIsDeterministic() {
        assertFalse(BehaviorGraphViewportController.exceedsDragThreshold(
                new Point(10, 10),
                new Point(13, 14)
        ));
        assertTrue(BehaviorGraphViewportController.exceedsDragThreshold(
                new Point(10, 10),
                new Point(16, 10)
        ));
    }

    private static BehaviorGraphVisualizationSnapshot snapshot() {
        return new BehaviorGraphVisualizationSnapshot(
                new GraphId("F100", 9L),
                "Test Ship",
                1L,
                1L,
                Instant.parse("2026-07-30T09:00:00Z"),
                Optional.of(NormalizedEventType.SYSTEM_ENTRY),
                Optional.of(new EventOccurrenceId("occurrence-1")),
                Optional.of(new SystemEpisodeId("episode-1")),
                List.of(
                        new VisualizationNode(
                                NormalizedEventType.SYSTEM_ENTRY,
                                "System Entry",
                                1L
                        ),
                        new VisualizationNode(
                                NormalizedEventType.TOUCHDOWN,
                                "Touchdown",
                                1L
                        )
                ),
                List.of()
        );
    }

    private static void dispatch(
            BehaviorGraphCanvas canvas,
            int id,
            Point point,
            int absoluteX,
            int absoluteY,
            int button,
            int modifiers
    ) {
        canvas.dispatchEvent(new MouseEvent(
                canvas,
                id,
                1L,
                modifiers,
                point.x,
                point.y,
                absoluteX,
                absoluteY,
                1,
                false,
                button
        ));
    }
}
