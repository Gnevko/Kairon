package kairon.ui.swing.behaviorgraph;

import kairon.behavior.event.BehaviorGraphEventSource;
import kairon.behavior.graph.ActiveEpisodeNodeOccurrencesSnapshot;
import kairon.behavior.graph.BehaviorGraphVisualizationQuery;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.graph.EventOccurrenceDetailsSnapshot;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.normalize.NormalizedEventType;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Window;
import java.util.Objects;
import java.util.Optional;

/**
 * Full-size graph overview with an on-demand occurrence dialog.
 */
public final class BehaviorGraphTab extends JPanel
        implements BehaviorGraphTabController.View,
        BehaviorGraphOccurrenceController.View {

    public static final String TITLE = "Behavior Graph";

    private final BehaviorGraphCanvas canvas;
    private final JScrollPane scrollPane;
    private final BehaviorGraphOccurrenceInspector occurrenceInspector;
    private final Window ownerWindow;
    private final BehaviorGraphOccurrenceDialogFactory dialogFactory;
    private final BehaviorGraphViewportController viewportController;
    private final BehaviorGraphOccurrenceController occurrenceController;
    private final BehaviorGraphTabController controller;

    private BehaviorGraphOccurrenceDialogHandle occurrenceDialog;
    private boolean disposed;

    public BehaviorGraphTab(
            BehaviorGraphVisualizationQuery query,
            BehaviorGraphEventSource eventSource,
            Window ownerWindow
    ) {
        this(
                query,
                eventSource,
                Objects.requireNonNull(ownerWindow, "ownerWindow"),
                BehaviorGraphOccurrenceDialog::new
        );
    }

    BehaviorGraphTab(
            BehaviorGraphVisualizationQuery query,
            BehaviorGraphEventSource eventSource,
            Window ownerWindow,
            BehaviorGraphOccurrenceDialogFactory dialogFactory
    ) {
        super(new BorderLayout());
        requireEdt();
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(eventSource, "eventSource");
        this.ownerWindow = ownerWindow;
        this.dialogFactory = Objects.requireNonNull(
                dialogFactory,
                "dialogFactory"
        );

        Color background = uiColor(
                "Kairon.Graph.background",
                uiColor("Panel.background", new Color(0x07, 0x10, 0x19))
        );
        setBackground(background);
        canvas = new BehaviorGraphCanvas();
        scrollPane = new JScrollPane(
                canvas,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        scrollPane.setBorder(BorderFactory.createLineBorder(uiColor(
                "Kairon.Graph.border",
                uiColor("Separator.foreground", new Color(0x1E, 0x3B, 0x4A))
        )));
        scrollPane.getViewport().setBackground(background);
        scrollPane.setWheelScrollingEnabled(true);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(24);
        scrollPane.getVerticalScrollBar().setUnitIncrement(24);
        scrollPane.setMinimumSize(new Dimension(320, 220));

        occurrenceInspector = new BehaviorGraphOccurrenceInspector();
        occurrenceController = new BehaviorGraphOccurrenceController(
                query,
                this
        );
        occurrenceController.setInspectorVisible(false);
        viewportController = new BehaviorGraphViewportController(
                scrollPane,
                canvas,
                this::selectNodeFromGraph
        );
        occurrenceInspector.setSelectionListener(
                occurrenceController::selectOccurrence
        );

        add(scrollPane, BorderLayout.CENTER);

        controller = new BehaviorGraphTabController(
                query,
                eventSource,
                this
        );

        getAccessibleContext().setAccessibleName(TITLE);
        getAccessibleContext().setAccessibleDescription(
                "Aggregated behavior graph for the active ship."
        );
    }

    public void setTabSelected(boolean selected) {
        requireEdt();
        if (!disposed) {
            controller.setSelected(selected);
        }
    }

    public void dispose() {
        requireEdt();
        if (disposed) {
            return;
        }
        disposed = true;
        controller.dispose();
        occurrenceController.dispose();
        viewportController.dispose();
        if (occurrenceDialog != null) {
            occurrenceDialog.dispose();
        }
    }

    BehaviorGraphCanvas canvas() {
        return canvas;
    }

    JScrollPane scrollPane() {
        return scrollPane;
    }

    BehaviorGraphOccurrenceInspector occurrenceInspector() {
        return occurrenceInspector;
    }

    Optional<BehaviorGraphOccurrenceDialogHandle> occurrenceDialog() {
        return Optional.ofNullable(occurrenceDialog);
    }

    void selectNodeFromGraph(NormalizedEventType eventType) {
        requireEdt();
        if (disposed) {
            return;
        }
        boolean alreadySelected = occurrenceController
                .selectedEventType()
                .filter(eventType::equals)
                .isPresent();
        occurrenceController.selectNode(eventType);
        if (occurrenceController.selectedEventType()
                .filter(eventType::equals)
                .isEmpty()) {
            return;
        }
        if (alreadySelected) {
            occurrenceController.refreshSelectedNode();
        }
        occurrenceDialog().orElseGet(this::createOccurrenceDialog)
                .showForExplicitSelection();
        occurrenceController.setInspectorVisible(true);
        controller.setAuxiliaryViewActive(true);
    }

    @Override
    public boolean applySnapshot(
            BehaviorGraphVisualizationSnapshot snapshot,
            boolean fullRelayout
    ) {
        requireEdt();
        boolean relayout = canvas.setSnapshot(snapshot, fullRelayout);
        occurrenceController.onGraphSnapshotApplied(snapshot);
        return relayout;
    }

    @Override
    public void showNoActiveShip() {
        requireEdt();
        canvas.showNoActiveShip();
        occurrenceController.onGraphUnavailable();
    }

    @Override
    public void showEmptyGraph() {
        requireEdt();
        canvas.showEmptyGraph();
        occurrenceController.onGraphUnavailable();
    }

    @Override
    public void showRefreshError() {
        requireEdt();
        canvas.showRefreshError();
    }

    @Override
    public boolean hasGraphData() {
        requireEdt();
        return canvas.hasGraphData();
    }

    @Override
    public void centerCurrentNodeLater() {
        requireEdt();
        viewportController.centerCurrentNodeLater();
    }

    @Override
    public void setSelectedNode(
            Optional<NormalizedEventType> eventType
    ) {
        requireEdt();
        canvas.setSelectedNode(eventType);
    }

    @Override
    public void makeNodeVisible(NormalizedEventType eventType) {
        requireEdt();
        canvas.makeNodeVisible(eventType);
    }

    @Override
    public void showInitial() {
        requireEdt();
        occurrenceInspector.showInitial();
        if (occurrenceDialog != null) {
            occurrenceDialog.occurrencesApplied();
        }
    }

    @Override
    public void showSelectedNodePending(
            String displayName,
            long activeOccurrenceCount,
            boolean activeEpisodePresent
    ) {
        requireEdt();
        occurrenceInspector.showSelectedNodePending(
                displayName,
                activeOccurrenceCount,
                activeEpisodePresent
        );
    }

    @Override
    public Optional<EventOccurrenceId> applyOccurrences(
            ActiveEpisodeNodeOccurrencesSnapshot snapshot,
            Optional<EventOccurrenceId> occurrenceToPreserve,
            boolean selectLatest
    ) {
        requireEdt();
        Optional<EventOccurrenceId> selected =
                occurrenceInspector.applyOccurrences(
                snapshot,
                occurrenceToPreserve,
                selectLatest
        );
        if (occurrenceDialog != null) {
            occurrenceDialog.occurrencesApplied();
        }
        return selected;
    }

    @Override
    public void showDetails(EventOccurrenceDetailsSnapshot details) {
        requireEdt();
        occurrenceInspector.showDetails(details);
    }

    @Override
    public void showNoOccurrenceSelected() {
        requireEdt();
        occurrenceInspector.showNoOccurrenceSelected();
    }

    @Override
    public void showLoadError() {
        requireEdt();
        occurrenceInspector.showLoadError();
        if (occurrenceDialog != null) {
            occurrenceDialog.occurrencesApplied();
        }
    }

    private BehaviorGraphOccurrenceDialogHandle createOccurrenceDialog() {
        requireEdt();
        if (occurrenceDialog == null) {
            occurrenceDialog = Objects.requireNonNull(
                    dialogFactory.create(
                            ownerWindow,
                            occurrenceInspector,
                            () -> {
                                occurrenceController.setInspectorVisible(
                                        false
                                );
                                controller.setAuxiliaryViewActive(false);
                            }
                    ),
                    "occurrenceDialog"
            );
        }
        return occurrenceDialog;
    }

    private static Color uiColor(String key, Color fallback) {
        Color value = UIManager.getColor(key);
        return value == null ? fallback : value;
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "Behavior graph tab changes must run on the Swing EDT"
            );
        }
    }
}
