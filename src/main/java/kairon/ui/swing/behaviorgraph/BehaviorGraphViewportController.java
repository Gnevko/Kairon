package kairon.ui.swing.behaviorgraph;

import kairon.behavior.normalize.NormalizedEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Owns viewport centering and grab-to-pan interaction for the behavior graph.
 */
final class BehaviorGraphViewportController {

    static final int DRAG_THRESHOLD_PIXELS = 5;

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    BehaviorGraphViewportController.class
            );

    private final JScrollPane scrollPane;
    private final BehaviorGraphCanvas canvas;
    private final Consumer<NormalizedEventType> nodeSelectionListener;
    private final MouseAdapter panHandler = new PanHandler();

    private volatile boolean disposed;
    private boolean panning;
    private Point mouseAtPress;
    private Point viewportAtPress;
    private Cursor cursorBeforePan;
    private Optional<NormalizedEventType>
            nodeAtPress = Optional.empty();
    private Point nodePressScreen;
    private boolean nodeDrag;

    BehaviorGraphViewportController(
            JScrollPane scrollPane,
            BehaviorGraphCanvas canvas
    ) {
        this(scrollPane, canvas, ignored -> {
        });
    }

    BehaviorGraphViewportController(
            JScrollPane scrollPane,
            BehaviorGraphCanvas canvas,
            Consumer<NormalizedEventType> nodeSelectionListener
    ) {
        requireEdt();
        this.scrollPane = Objects.requireNonNull(scrollPane, "scrollPane");
        this.canvas = Objects.requireNonNull(canvas, "canvas");
        this.nodeSelectionListener = Objects.requireNonNull(
                nodeSelectionListener,
                "nodeSelectionListener"
        );
        if (scrollPane.getViewport().getView() != canvas) {
            throw new IllegalArgumentException(
                    "scrollPane viewport must contain canvas"
            );
        }
        canvas.addMouseListener(panHandler);
        canvas.addMouseMotionListener(panHandler);
    }

    /**
     * Centers the current structural node after pending Swing layout work.
     */
    void centerCurrentNodeLater() {
        if (disposed) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (disposed) {
                return;
            }
            /*
             * One additional queue turn lets a newly selected tab complete its
             * viewport layout before its extent is used for centering.
             */
            SwingUtilities.invokeLater(() -> {
                if (!disposed) {
                    centerCurrentNode();
                }
            });
        });
    }

    /**
     * Immediately centers the current structural node when called on the EDT.
     *
     * @return {@code true} when a current node was available
     */
    boolean centerCurrentNode() {
        requireEdt();
        if (disposed) {
            return false;
        }
        JViewport viewport = scrollPane.getViewport();
        Optional<Point> position = centeredViewportPosition(
                canvas.currentNodeCenter(),
                viewport.getExtentSize(),
                viewport.getViewSize()
        );
        position.ifPresent(viewport::setViewPosition);
        if (position.isPresent()) {
            LOGGER.debug(
                    "BEHAVIOR_GRAPH_VIEWPORT_CENTERED x={} y={}",
                    position.orElseThrow().x,
                    position.orElseThrow().y
            );
        }
        return position.isPresent();
    }

    void dispose() {
        requireEdt();
        if (disposed) {
            return;
        }
        disposed = true;
        finishPanning();
        clearNodePress();
        canvas.setCursor(Cursor.getDefaultCursor());
        canvas.removeMouseListener(panHandler);
        canvas.removeMouseMotionListener(panHandler);
    }

    static Optional<Point> centeredViewportPosition(
            Optional<? extends Point2D> nodeCenter,
            Dimension viewportExtent,
            Dimension canvasSize
    ) {
        Objects.requireNonNull(nodeCenter, "nodeCenter");
        return nodeCenter.map(center -> centeredViewportPosition(
                center,
                viewportExtent,
                canvasSize
        ));
    }

    static Point centeredViewportPosition(
            Point2D nodeCenter,
            Dimension viewportExtent,
            Dimension canvasSize
    ) {
        Objects.requireNonNull(nodeCenter, "nodeCenter");
        Objects.requireNonNull(viewportExtent, "viewportExtent");
        Objects.requireNonNull(canvasSize, "canvasSize");
        Point requested = new Point(
                roundedCoordinate(
                        nodeCenter.getX() - viewportExtent.getWidth() / 2.0
                ),
                roundedCoordinate(
                        nodeCenter.getY() - viewportExtent.getHeight() / 2.0
                )
        );
        return clampViewportPosition(
                requested,
                viewportExtent,
                canvasSize
        );
    }

    static Point panViewportPosition(
            Point viewportAtPress,
            Point mouseAtPress,
            Point currentMouse,
            Dimension viewportExtent,
            Dimension canvasSize
    ) {
        Objects.requireNonNull(viewportAtPress, "viewportAtPress");
        Objects.requireNonNull(mouseAtPress, "mouseAtPress");
        Objects.requireNonNull(currentMouse, "currentMouse");
        Point requested = new Point(
                pannedCoordinate(
                        viewportAtPress.x,
                        mouseAtPress.x,
                        currentMouse.x
                ),
                pannedCoordinate(
                        viewportAtPress.y,
                        mouseAtPress.y,
                        currentMouse.y
                )
        );
        return clampViewportPosition(
                requested,
                viewportExtent,
                canvasSize
        );
    }

    static Point clampViewportPosition(
            Point requested,
            Dimension viewportExtent,
            Dimension canvasSize
    ) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(viewportExtent, "viewportExtent");
        Objects.requireNonNull(canvasSize, "canvasSize");
        int maximumX = Math.max(0, canvasSize.width - viewportExtent.width);
        int maximumY = Math.max(0, canvasSize.height - viewportExtent.height);
        return new Point(
                Math.clamp(requested.x, 0, maximumX),
                Math.clamp(requested.y, 0, maximumY)
        );
    }

    private void startPanning(MouseEvent event) {
        boolean middleButton = SwingUtilities.isMiddleMouseButton(event);
        Optional<NormalizedEventType> hit =
                canvas.findNodeAt(event.getPoint());
        if (SwingUtilities.isLeftMouseButton(event) && hit.isPresent()) {
            nodeAtPress = hit;
            nodePressScreen = event.getLocationOnScreen();
            nodeDrag = false;
            return;
        }
        boolean emptyBackground = SwingUtilities.isLeftMouseButton(event);
        if (!middleButton && !emptyBackground) {
            return;
        }
        if (panning) {
            finishPanning();
        }
        panning = true;
        mouseAtPress = event.getLocationOnScreen();
        viewportAtPress = scrollPane.getViewport().getViewPosition();
        cursorBeforePan = canvas.getCursor();
        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        LOGGER.debug(
                "BEHAVIOR_GRAPH_PANNING_STARTED button={}",
                middleButton ? "MIDDLE" : "LEFT"
        );
        event.consume();
    }

    private void continuePanning(MouseEvent event) {
        if (nodeAtPress.isPresent()) {
            nodeDrag = nodeDrag || exceedsDragThreshold(
                    nodePressScreen,
                    event.getLocationOnScreen()
            );
            if (nodeDrag) {
                event.consume();
            }
            return;
        }
        if (!panning || disposed) {
            return;
        }
        JViewport viewport = scrollPane.getViewport();
        viewport.setViewPosition(panViewportPosition(
                viewportAtPress,
                mouseAtPress,
                event.getLocationOnScreen(),
                viewport.getExtentSize(),
                viewport.getViewSize()
        ));
        event.consume();
    }

    private void finishPanning() {
        if (!panning) {
            return;
        }
        panning = false;
        canvas.setCursor(cursorBeforePan);
        cursorBeforePan = null;
        mouseAtPress = null;
        viewportAtPress = null;
        LOGGER.debug("BEHAVIOR_GRAPH_PANNING_ENDED");
    }

    private void completeNodeClick(MouseEvent event) {
        if (nodeAtPress.isEmpty()) {
            return;
        }
        Optional<NormalizedEventType> pressed =
                nodeAtPress;
        Optional<NormalizedEventType> released =
                canvas.findNodeAt(event.getPoint());
        boolean click = !nodeDrag && pressed.equals(released);
        clearNodePress();
        if (click) {
            nodeSelectionListener.accept(pressed.orElseThrow());
            event.consume();
        }
    }

    private void clearNodePress() {
        nodeAtPress = Optional.empty();
        nodePressScreen = null;
        nodeDrag = false;
    }

    private void updateHoverCursor(MouseEvent event) {
        if (panning || disposed) {
            return;
        }
        Cursor next = canvas.findNodeAt(event.getPoint()).isPresent()
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor();
        if (!next.equals(canvas.getCursor())) {
            canvas.setCursor(next);
        }
    }

    static boolean exceedsDragThreshold(Point start, Point current) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(current, "current");
        long deltaX = (long) current.x - start.x;
        long deltaY = (long) current.y - start.y;
        long threshold = DRAG_THRESHOLD_PIXELS;
        return deltaX * deltaX + deltaY * deltaY
                > threshold * threshold;
    }

    private static int roundedCoordinate(double value) {
        if (Double.isNaN(value) || value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(value);
    }

    private static int pannedCoordinate(
            int viewportCoordinate,
            int pressCoordinate,
            int currentCoordinate
    ) {
        long result = (long) viewportCoordinate
                - ((long) currentCoordinate - pressCoordinate);
        return (int) Math.clamp(
                result,
                (long) Integer.MIN_VALUE,
                (long) Integer.MAX_VALUE
        );
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "Behavior graph viewport changes must run on the EDT"
            );
        }
    }

    private final class PanHandler extends MouseAdapter {

        @Override
        public void mousePressed(MouseEvent event) {
            if (!disposed) {
                startPanning(event);
            }
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            continuePanning(event);
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            if (nodeAtPress.isPresent()) {
                completeNodeClick(event);
                updateHoverCursor(event);
                return;
            }
            if (panning) {
                finishPanning();
                event.consume();
            }
            updateHoverCursor(event);
        }

        @Override
        public void mouseMoved(MouseEvent event) {
            updateHoverCursor(event);
        }

        @Override
        public void mouseExited(MouseEvent event) {
            if (!panning && nodeAtPress.isEmpty()) {
                canvas.setCursor(Cursor.getDefaultCursor());
            }
        }
    }
}
