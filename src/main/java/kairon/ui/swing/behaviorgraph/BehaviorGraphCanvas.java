package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.EdgeRenderData;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.EdgeRenderKind;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.NodeRenderData;

import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Paint-only Swing surface for an already aggregated behavior graph.
 */
public final class BehaviorGraphCanvas extends JPanel implements Scrollable {

    public static final String EMPTY_MESSAGE =
            "No behavior graph data for the active ship yet.";
    public static final String NO_ACTIVE_SHIP_MESSAGE =
            "No active ship is available.";
    public static final String REFRESH_ERROR_MESSAGE =
            "Unable to refresh the behavior graph.";

    private static final int SCROLL_UNIT = 24;
    private static final int SCROLL_BLOCK = 160;
    private static final int LABEL_BACKGROUND_PADDING_X = 3;
    private static final int LABEL_BACKGROUND_PADDING_Y = 2;
    private static final List<EdgeRenderKind> SPECIAL_EDGE_KINDS = List.of(
            EdgeRenderKind.SAME_LEVEL,
            EdgeRenderKind.BACKWARD,
            EdgeRenderKind.SELF
    );

    private BehaviorGraphRenderModelFactory modelFactory;
    private BehaviorGraphVisualizationSnapshot snapshot;
    private BehaviorGraphRenderModel renderModel;
    private Optional<NormalizedEventType> selectedNode = Optional.empty();
    private String stateMessage = NO_ACTIVE_SHIP_MESSAGE;
    private boolean refreshError;
    private boolean initialized;
    private boolean metricsRelayoutScheduled;

    public BehaviorGraphCanvas() {
        requireEdt();
        modelFactory = new BehaviorGraphRenderModelFactory(
                new LayeredBehaviorGraphLayoutEngine(),
                new EdgeIntensityScale()
        );
        initialized = true;
        addPropertyChangeListener("font", event ->
                scheduleMetricsRelayout());
        setOpaque(true);
        setFocusable(true);
        setToolTipText(null);
        getAccessibleContext().setAccessibleName("Behavior Graph");
        getAccessibleContext().setAccessibleDescription(
                "Aggregated behavior graph for the active ship."
        );
        refreshUiDefaults();
        setPreferredSize(new Dimension(1, 1));
    }

    public boolean setSnapshot(
            BehaviorGraphVisualizationSnapshot newSnapshot,
            boolean fullRelayout
    ) {
        requireEdt();
        snapshot = Objects.requireNonNull(newSnapshot, "newSnapshot");
        boolean mustRelayout = fullRelayout
                || renderModel == null
                || renderModel.topologyVersion()
                != newSnapshot.topologyVersion();
        renderModel = modelFactory.create(
                newSnapshot,
                getFontMetrics(getFont()),
                getFontMetrics(getFont().deriveFont(Font.BOLD)),
                renderModel,
                mustRelayout
        );
        selectedNode = selectedNode.filter(renderModel.nodes()::containsKey);
        stateMessage = newSnapshot.nodes().isEmpty()
                ? EMPTY_MESSAGE
                : null;
        refreshError = false;
        updatePreferredSize();
        repaint();
        return mustRelayout;
    }

    public void showNoActiveShip() {
        requireEdt();
        snapshot = null;
        renderModel = null;
        selectedNode = Optional.empty();
        stateMessage = NO_ACTIVE_SHIP_MESSAGE;
        refreshError = false;
        setPreferredSize(new Dimension(1, 1));
        revalidate();
        repaint();
    }

    public void showEmptyGraph() {
        requireEdt();
        snapshot = null;
        renderModel = null;
        selectedNode = Optional.empty();
        stateMessage = EMPTY_MESSAGE;
        refreshError = false;
        setPreferredSize(new Dimension(1, 1));
        revalidate();
        repaint();
    }

    public void showRefreshError() {
        requireEdt();
        refreshError = true;
        repaint();
    }

    public boolean hasGraphData() {
        requireEdt();
        return renderModel != null && !renderModel.nodes().isEmpty();
    }

    public Optional<Point2D.Double> currentNodeCenter() {
        requireEdt();
        if (renderModel == null || renderModel.currentNode().isEmpty()) {
            return Optional.empty();
        }
        NodeRenderData node = renderModel.nodes().get(
                renderModel.currentNode().orElseThrow()
        );
        return node == null
                ? Optional.empty()
                : Optional.of(new Point2D.Double(
                        node.centerX(),
                        node.centerY()
                ));
    }

    public boolean isNodeAt(Point point) {
        return findNodeAt(point).isPresent();
    }

    /**
     * Resolves a structural node from its circle or visible label bounds.
     */
    public Optional<NormalizedEventType> findNodeAt(Point point) {
        requireEdt();
        Objects.requireNonNull(point, "point");
        if (renderModel == null) {
            return Optional.empty();
        }
        return findNodeAt(renderModel, selectedNode, point);
    }

    static Optional<NormalizedEventType> findNodeAt(
            BehaviorGraphRenderModel model,
            Optional<NormalizedEventType> selected,
            Point point
    ) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(point, "point");
        return model.nodes().values().stream()
                .filter(node -> node.circleBounds().contains(point)
                        || node.labelBounds().contains(point))
                .sorted(hitPriority(selected, point))
                .map(NodeRenderData::eventType)
                .findFirst();
    }

    public void setSelectedNode(
            Optional<NormalizedEventType> eventType
    ) {
        requireEdt();
        Objects.requireNonNull(eventType, "eventType");
        selectedNode = eventType.filter(type ->
                renderModel != null
                        && renderModel.nodes().containsKey(type));
        repaint();
    }

    public Optional<NormalizedEventType> selectedNode() {
        requireEdt();
        return selectedNode;
    }

    /**
     * Scrolls only as much as Swing needs to expose the selected node.
     */
    public void makeNodeVisible(NormalizedEventType eventType) {
        requireEdt();
        Objects.requireNonNull(eventType, "eventType");
        nodeVisibleBounds(eventType).ifPresent(this::scrollRectToVisible);
    }

    Optional<Rectangle> nodeVisibleBounds(
            NormalizedEventType eventType
    ) {
        requireEdt();
        if (renderModel == null) {
            return Optional.empty();
        }
        NodeRenderData node = renderModel.nodes().get(eventType);
        if (node == null) {
            return Optional.empty();
        }
        Rectangle2D bounds = node.labelBounds()
                .createUnion(node.circleBounds().getBounds2D());
        int padding = 8;
        return Optional.of(new Rectangle(
                (int) Math.floor(bounds.getX()) - padding,
                (int) Math.floor(bounds.getY()) - padding,
                (int) Math.ceil(bounds.getWidth()) + padding * 2,
                (int) Math.ceil(bounds.getHeight()) + padding * 2
        ));
    }

    BehaviorGraphRenderModel renderModel() {
        return renderModel;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        refreshUiDefaults();
        scheduleMetricsRelayout();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D canvas = (Graphics2D) graphics.create();
        try {
            canvas.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );
            canvas.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            );
            canvas.setRenderingHint(
                    RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE
            );

            BehaviorGraphRenderModel model = renderModel;
            if (model != null && !model.nodes().isEmpty()) {
                paintEdges(canvas, model.edges(), EdgeRenderKind.FORWARD);
                paintSpecialEdges(canvas, model.edges());
                paintOrdinaryNodes(canvas, model);
                paintOrdinaryLabels(canvas, model);
                paintCurrentNode(canvas, model);
                paintSelectedNode(canvas, model);
                paintModelReachLegend(canvas);
            }
            if (stateMessage != null) {
                paintCenteredMessage(canvas, stateMessage, mutedColor());
            }
            if (refreshError) {
                paintErrorOverlay(canvas);
            }
        } finally {
            canvas.dispose();
        }
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(
            Rectangle visibleRect,
            int orientation,
            int direction
    ) {
        return SCROLL_UNIT;
    }

    @Override
    public int getScrollableBlockIncrement(
            Rectangle visibleRect,
            int orientation,
            int direction
    ) {
        int visible = orientation == SwingConstants.HORIZONTAL
                ? visibleRect.width
                : visibleRect.height;
        return Math.max(SCROLL_BLOCK, visible - SCROLL_UNIT);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport viewport
                && viewport.getWidth() > getPreferredSize().width;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport viewport
                && viewport.getHeight() > getPreferredSize().height;
    }

    private void paintEdges(
            Graphics2D canvas,
            List<EdgeRenderData> edges,
            EdgeRenderKind kind
    ) {
        for (EdgeRenderData edge : edges) {
            if (edge.kind() == kind) {
                paintEdge(canvas, edge);
            }
        }
    }

    private void paintSpecialEdges(
            Graphics2D canvas,
            List<EdgeRenderData> edges
    ) {
        for (EdgeRenderKind kind : SPECIAL_EDGE_KINDS) {
            paintEdges(canvas, edges, kind);
        }
    }

    private void paintEdge(Graphics2D canvas, EdgeRenderData edge) {
        Color color = interpolate(
                getBackground(),
                edgeBaseColor(),
                edge.intensity()
        );
        canvas.setColor(color);
        canvas.setStroke(new BasicStroke(
                (float) (1.1 + 0.45 * edge.intensity()),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND
        ));
        canvas.draw(edge.path());
        canvas.fill(edge.arrowHead());
    }

    private void paintOrdinaryNodes(
            Graphics2D canvas,
            BehaviorGraphRenderModel model
    ) {
        for (NodeRenderData node : model.nodes().values()) {
            if (node.current()) {
                continue;
            }
            canvas.setColor(nodeFillColor(node.modelReach()));
            canvas.fill(node.circleBounds());
            canvas.setColor(nodeOutlineColor());
            canvas.setStroke(nodeOutlineStroke(node.modelReach()));
            canvas.draw(node.circleBounds());
        }
        canvas.setStroke(new BasicStroke(1.4f));
    }

    /**
     * How a node says whether the model ever hears about it.
     *
     * <p>Two channels rather than one, so the distinction survives a theme and
     * a monochrome screen: the outline is solid for a type every observation of
     * which opens a turn, dashed for one judged per observation, and dotted for
     * one the model is never shown; the fill fades in the same order. Colour
     * alone would be lost in the dark theme, and stroke alone is hard to see on
     * a small node.</p>
     */
    private static BasicStroke nodeOutlineStroke(NodeModelReach reach) {
        return switch (reach) {
            case ALWAYS -> new BasicStroke(1.8f);
            case SOMETIMES -> new BasicStroke(
                    1.4f,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_ROUND,
                    1.0f,
                    new float[] {5.0f, 3.5f},
                    0.0f
            );
            case NEVER -> new BasicStroke(
                    1.0f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND,
                    1.0f,
                    new float[] {1.0f, 3.0f},
                    0.0f
            );
        };
    }

    private Color nodeFillColor(NodeModelReach reach) {
        Color base = nodeFillColor();
        return switch (reach) {
            case ALWAYS -> base;
            case SOMETIMES -> interpolate(getBackground(), base, 0.55);
            case NEVER -> interpolate(getBackground(), base, 0.2);
        };
    }

    /**
     * The key to the outlines, in screen space and in the corner.
     *
     * <p>Three samples drawn with the same strokes and fills the nodes use, so
     * the legend cannot describe a picture the canvas is not painting.</p>
     */
    private void paintModelReachLegend(Graphics2D canvas) {
        canvas.setFont(getFont());
        FontMetrics metrics = canvas.getFontMetrics();
        int diameter = Math.max(10, metrics.getAscent() - 2);
        int rowHeight = Math.max(diameter + 6, metrics.getHeight() + 2);
        int left = 12;
        int bottom = getHeight() - 12;
        NodeModelReach[] order = {
            NodeModelReach.ALWAYS,
            NodeModelReach.SOMETIMES,
            NodeModelReach.NEVER
        };
        int top = bottom - rowHeight * order.length;
        for (int index = 0; index < order.length; index++) {
            NodeModelReach reach = order[index];
            int centerY = top + rowHeight * index + rowHeight / 2;
            Ellipse2D.Double sample = new Ellipse2D.Double(
                    left,
                    centerY - diameter / 2.0,
                    diameter,
                    diameter
            );
            canvas.setColor(nodeFillColor(reach));
            canvas.fill(sample);
            canvas.setColor(nodeOutlineColor());
            canvas.setStroke(nodeOutlineStroke(reach));
            canvas.draw(sample);
            canvas.setColor(mutedColor());
            canvas.drawString(
                    reach.label(),
                    left + diameter + 8,
                    centerY + metrics.getAscent() / 2 - 1
            );
        }
        canvas.setStroke(new BasicStroke(1.4f));
    }

    private void paintOrdinaryLabels(
            Graphics2D canvas,
            BehaviorGraphRenderModel model
    ) {
        canvas.setFont(getFont());
        canvas.setColor(labelColor());
        FontMetrics metrics = canvas.getFontMetrics();
        for (NodeRenderData node : model.nodes().values()) {
            if (!node.current()) {
                drawLabel(canvas, metrics, node);
            }
        }
    }

    private void paintCurrentNode(
            Graphics2D canvas,
            BehaviorGraphRenderModel model
    ) {
        Optional<NormalizedEventType> currentType = model.currentNode();
        if (currentType.isEmpty()) {
            return;
        }
        NodeRenderData node = model.nodes().get(currentType.orElseThrow());
        if (node == null) {
            return;
        }

        double haloPadding = 5.0;
        canvas.setColor(withAlpha(currentNodeColor(), 70));
        canvas.fillOval(
                (int) Math.floor(node.circleBounds().x - haloPadding),
                (int) Math.floor(node.circleBounds().y - haloPadding),
                (int) Math.ceil(node.circleBounds().width
                        + haloPadding * 2.0),
                (int) Math.ceil(node.circleBounds().height
                        + haloPadding * 2.0)
        );
        canvas.setColor(currentNodeColor());
        canvas.fill(node.circleBounds());
        canvas.setColor(currentNodeOutlineColor());
        canvas.setStroke(new BasicStroke(2.5f));
        canvas.draw(node.circleBounds());
        canvas.setStroke(new BasicStroke(1.2f));
        canvas.drawOval(
                (int) Math.floor(node.circleBounds().x - 3.0),
                (int) Math.floor(node.circleBounds().y - 3.0),
                (int) Math.ceil(node.circleBounds().width + 6.0),
                (int) Math.ceil(node.circleBounds().height + 6.0)
        );

        Font bold = getFont().deriveFont(Font.BOLD);
        canvas.setFont(bold);
        canvas.setColor(labelColor());
        drawLabel(canvas, canvas.getFontMetrics(bold), node);
    }

    private void paintSelectedNode(
            Graphics2D canvas,
            BehaviorGraphRenderModel model
    ) {
        if (selectedNode.isEmpty()) {
            return;
        }
        NodeRenderData node = model.nodes().get(selectedNode.orElseThrow());
        if (node == null) {
            return;
        }

        double ringPadding = node.current() ? 8.0 : 6.0;
        canvas.setColor(selectedNodeColor());
        canvas.setStroke(new BasicStroke(
                2.1f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                10.0f,
                new float[]{6.0f, 4.0f},
                0.0f
        ));
        canvas.drawOval(
                (int) Math.floor(node.circleBounds().x - ringPadding),
                (int) Math.floor(node.circleBounds().y - ringPadding),
                (int) Math.ceil(node.circleBounds().width
                        + ringPadding * 2.0),
                (int) Math.ceil(node.circleBounds().height
                        + ringPadding * 2.0)
        );
    }

    private void drawLabel(
            Graphics2D canvas,
            FontMetrics metrics,
            NodeRenderData node
    ) {
        Color labelColor = canvas.getColor();
        int backgroundX = (int) Math.floor(
                node.labelBounds().x - LABEL_BACKGROUND_PADDING_X
        );
        int backgroundY = (int) Math.floor(
                node.labelBounds().y - LABEL_BACKGROUND_PADDING_Y
        );
        int backgroundWidth = (int) Math.ceil(
                node.labelBounds().width
                        + LABEL_BACKGROUND_PADDING_X * 2.0
        );
        int backgroundHeight = (int) Math.ceil(
                node.labelBounds().height
                        + LABEL_BACKGROUND_PADDING_Y * 2.0
        );
        canvas.setColor(getBackground());
        canvas.fillRoundRect(
                backgroundX,
                backgroundY,
                backgroundWidth,
                backgroundHeight,
                6,
                6
        );
        canvas.setColor(labelColor);
        float baseline = (float) (
                node.centerY()
                        + (metrics.getAscent() - metrics.getDescent()) / 2.0
        );
        canvas.drawString(
                node.label(),
                (float) node.labelBounds().x,
                baseline
        );
    }

    private void paintCenteredMessage(
            Graphics2D canvas,
            String message,
            Color color
    ) {
        Rectangle visible = getVisibleRect();
        FontMetrics metrics = canvas.getFontMetrics(getFont());
        int x = visible.x
                + Math.max(0, (visible.width - metrics.stringWidth(message)) / 2);
        int y = visible.y
                + Math.max(
                        metrics.getAscent(),
                        (visible.height
                                + metrics.getAscent()
                                - metrics.getDescent()) / 2
                );
        canvas.setFont(getFont());
        canvas.setColor(color);
        canvas.drawString(message, x, y);
    }

    private void paintErrorOverlay(Graphics2D canvas) {
        Rectangle visible = getVisibleRect();
        Font bold = getFont().deriveFont(Font.BOLD);
        FontMetrics metrics = canvas.getFontMetrics(bold);
        int paddingX = 14;
        int paddingY = 8;
        int width = metrics.stringWidth(REFRESH_ERROR_MESSAGE)
                + paddingX * 2;
        int height = metrics.getHeight() + paddingY * 2;
        int x = visible.x + Math.max(8, (visible.width - width) / 2);
        int y = visible.y + 16;

        canvas.setColor(interpolate(
                getBackground(),
                errorColor(),
                0.24
        ));
        canvas.fillRoundRect(x, y, width, height, 10, 10);
        canvas.setColor(errorColor());
        canvas.drawRoundRect(x, y, width, height, 10, 10);
        canvas.setFont(bold);
        canvas.drawString(
                REFRESH_ERROR_MESSAGE,
                x + paddingX,
                y + paddingY + metrics.getAscent()
        );
    }

    private void updatePreferredSize() {
        Dimension next = new Dimension(
                renderModel.canvasWidth(),
                renderModel.canvasHeight()
        );
        if (!next.equals(getPreferredSize())) {
            setPreferredSize(next);
            revalidate();
        }
    }

    private void refreshUiDefaults() {
        Color background = color("Kairon.Graph.background",
                color("Panel.background", new Color(0x07, 0x10, 0x19)));
        setBackground(background);
        setForeground(color(
                "Label.foreground",
                new Color(0xD7, 0xED, 0xF7)
        ));
        Font font = UIManager.getFont("Label.font");
        if (font != null) {
            setFont(font);
        }
    }

    private void scheduleMetricsRelayout() {
        if (!initialized
                || snapshot == null
                || modelFactory == null
                || metricsRelayoutScheduled) {
            return;
        }
        metricsRelayoutScheduled = true;
        SwingUtilities.invokeLater(() -> {
            metricsRelayoutScheduled = false;
            if (snapshot != null) {
                setSnapshot(snapshot, true);
            }
        });
    }

    private Color labelColor() {
        return color("Label.foreground", getForeground());
    }

    private Color mutedColor() {
        return color(
                "Kairon.Graph.muted",
                interpolate(getBackground(), labelColor(), 0.65)
        );
    }

    private Color nodeFillColor() {
        return color(
                "Kairon.Graph.nodeFill",
                color("List.selectionBackground", getBackground().brighter())
        );
    }

    private Color nodeOutlineColor() {
        return color("Kairon.Graph.nodeOutline", labelColor());
    }

    private Color currentNodeColor() {
        return color(
                "Kairon.Graph.currentNode",
                color("Table.selectionForeground", new Color(0x26D0CE))
        );
    }

    private Color currentNodeOutlineColor() {
        return color(
                "Kairon.Graph.currentNodeOutline",
                color("List.selectionForeground", labelColor())
        );
    }

    private Color selectedNodeColor() {
        return color(
                "Kairon.Graph.selectedNode",
                color(
                        "Component.focusColor",
                        color("List.selectionBackground", labelColor())
                )
        );
    }

    private Color edgeBaseColor() {
        return color(
                "Kairon.Graph.edge",
                color("Label.foreground", getForeground())
        );
    }

    private Color errorColor() {
        return color(
                "Kairon.Graph.error",
                new Color(0xFF, 0x87, 0x66)
        );
    }

    private static Color color(String key, Color fallback) {
        Color value = UIManager.getColor(key);
        return value == null ? fallback : value;
    }

    private static Color interpolate(
            Color background,
            Color foreground,
            double amount
    ) {
        double clamped = Math.max(0.0, Math.min(1.0, amount));
        int red = interpolate(background.getRed(), foreground.getRed(), clamped);
        int green = interpolate(
                background.getGreen(),
                foreground.getGreen(),
                clamped
        );
        int blue = interpolate(
                background.getBlue(),
                foreground.getBlue(),
                clamped
        );
        return new Color(red, green, blue);
    }

    private static int interpolate(int start, int end, double amount) {
        return (int) Math.round(start + (end - start) * amount);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(
                color.getRed(),
                color.getGreen(),
                color.getBlue(),
                alpha
        );
    }

    private static Comparator<NodeRenderData> hitPriority(
            Optional<NormalizedEventType> selected,
            Point point
    ) {
        return Comparator
                .comparing((NodeRenderData node) -> !node.current())
                .thenComparing(node ->
                        !selected
                                .map(node.eventType()::equals)
                                .orElse(false))
                .thenComparing(node ->
                        !node.circleBounds().contains(point))
                .thenComparing(NodeRenderData::eventType);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "Behavior graph canvas mutations require the Swing EDT"
            );
        }
    }
}
