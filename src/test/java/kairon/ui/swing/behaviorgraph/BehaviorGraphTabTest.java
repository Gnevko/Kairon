package kairon.ui.swing.behaviorgraph;

import kairon.behavior.event.BehaviorGraphEventSource;
import kairon.behavior.event.BehaviorGraphListener;
import kairon.behavior.graph.ActiveEpisodeNodeOccurrencesSnapshot;
import kairon.behavior.graph.BehaviorGraphDisplayNameResolver;
import kairon.behavior.graph.BehaviorGraphVisualizationQuery;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationNode;
import kairon.behavior.graph.EventOccurrenceDetailsSnapshot;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;
import org.junit.jupiter.api.Test;

import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BehaviorGraphTabTest {

    private static final GraphId GRAPH_ID = new GraphId("F100", 9L);

    @Test
    void tabUsesFullAreaForGraphWithoutEmbeddedInspector()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FakeDialogFactory dialogs = new FakeDialogFactory();
            BehaviorGraphTab tab = new BehaviorGraphTab(
                    EmptyQuery.INSTANCE,
                    EmptyEventSource.INSTANCE,
                    null,
                    dialogs
            );
            try {
                assertEquals("Behavior Graph", BehaviorGraphTab.TITLE);
                assertEquals(1, tab.getComponentCount());
                assertSame(tab.scrollPane(), tab.getComponent(0));
                assertSame(
                        tab.canvas(),
                        tab.scrollPane().getViewport().getView()
                );
                assertFalse(containsHorizontalSplit(tab));
                assertFalse(SwingUtilities.isDescendingFrom(
                        tab.occurrenceInspector(),
                        tab
                ));
                assertTrue(tab.occurrenceDialog().isEmpty());
                assertTrue(
                        tab.canvas().getMouseListeners().length > 0
                );
                assertTrue(
                        tab.canvas().getMouseMotionListeners().length > 0
                );
            } finally {
                tab.dispose();
            }
        });
    }

    @Test
    void explicitNodeSelectionLazilyCreatesAndReusesDialog()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FakeDialogFactory dialogs = new FakeDialogFactory();
            BehaviorGraphTab tab = new BehaviorGraphTab(
                    EmptyQuery.INSTANCE,
                    EmptyEventSource.INSTANCE,
                    null,
                    dialogs
            );
            try {
                tab.applySnapshot(snapshot(), true);
                assertTrue(tab.occurrenceDialog().isEmpty());

                tab.selectNodeFromGraph(
                        NormalizedEventType.SYSTEM_ENTRY
                );
                FakeDialog first = dialogs.dialog;
                assertEquals(1, dialogs.creationCount);
                assertEquals(1, first.showCount);
                assertTrue(first.visible);
                assertSame(
                        tab.occurrenceInspector(),
                        first.inspector
                );

                tab.selectNodeFromGraph(
                        NormalizedEventType.TOUCHDOWN
                );
                assertEquals(1, dialogs.creationCount);
                assertSame(first, tab.occurrenceDialog().orElseThrow());
                assertEquals(2, first.showCount);
                assertEquals(
                        Optional.of(NormalizedEventType.TOUCHDOWN),
                        tab.canvas().selectedNode()
                );

                first.close();
                assertFalse(first.visible);
                assertEquals(
                        Optional.of(NormalizedEventType.TOUCHDOWN),
                        tab.canvas().selectedNode()
                );

                tab.selectNodeFromGraph(
                        NormalizedEventType.TOUCHDOWN
                );
                assertEquals(1, dialogs.creationCount);
                assertSame(first, tab.occurrenceDialog().orElseThrow());
                assertEquals(3, first.showCount);
                assertTrue(first.visible);
            } finally {
                tab.dispose();
            }
            assertTrue(dialogs.dialog.disposed);
        });
    }

    @Test
    void nodeCircleAndLabelClicksOpenDialogButDragsDoNot()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FakeDialogFactory dialogs = new FakeDialogFactory();
            BehaviorGraphTab tab = new BehaviorGraphTab(
                    EmptyQuery.INSTANCE,
                    EmptyEventSource.INSTANCE,
                    null,
                    dialogs
            );
            try {
                tab.applySnapshot(snapshot(), true);
                tab.setSize(900, 620);
                tab.doLayout();
                tab.scrollPane().doLayout();
                tab.canvas().setSize(
                        tab.canvas().getPreferredSize()
                );
                BehaviorGraphRenderModel.NodeRenderData node =
                        tab.canvas().renderModel().nodes().get(
                                NormalizedEventType.SYSTEM_ENTRY
                        );
                Point circle = new Point(
                        (int) Math.round(node.centerX()),
                        (int) Math.round(node.centerY())
                );
                Point label = new Point(
                        (int) Math.round(
                                node.labelBounds().getCenterX()
                        ),
                        (int) Math.round(
                                node.labelBounds().getCenterY()
                        )
                );

                click(tab.canvas(), circle, MouseEvent.BUTTON1);
                assertEquals(1, dialogs.creationCount);
                assertEquals(1, dialogs.dialog.showCount);
                dialogs.dialog.close();

                click(tab.canvas(), label, MouseEvent.BUTTON1);
                assertEquals(1, dialogs.creationCount);
                assertEquals(2, dialogs.dialog.showCount);
                dialogs.dialog.close();

                dispatch(
                        tab.canvas(),
                        MouseEvent.MOUSE_PRESSED,
                        circle,
                        500,
                        500,
                        MouseEvent.BUTTON1,
                        0
                );
                dispatch(
                        tab.canvas(),
                        MouseEvent.MOUSE_DRAGGED,
                        circle,
                        510,
                        500,
                        MouseEvent.NOBUTTON,
                        InputEvent.BUTTON1_DOWN_MASK
                );
                dispatch(
                        tab.canvas(),
                        MouseEvent.MOUSE_RELEASED,
                        circle,
                        510,
                        500,
                        MouseEvent.BUTTON1,
                        0
                );
                assertEquals(2, dialogs.dialog.showCount);

                Point background = new Point(2, 2);
                click(
                        tab.canvas(),
                        background,
                        MouseEvent.BUTTON1
                );
                click(
                        tab.canvas(),
                        circle,
                        MouseEvent.BUTTON2
                );
                assertEquals(2, dialogs.dialog.showCount);
            } finally {
                tab.dispose();
            }
        });
    }

    private static boolean containsHorizontalSplit(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JSplitPane split
                    && split.getOrientation()
                    == JSplitPane.HORIZONTAL_SPLIT) {
                return true;
            }
            if (component instanceof Container child
                    && containsHorizontalSplit(child)) {
                return true;
            }
        }
        return false;
    }

    private static void click(
            BehaviorGraphCanvas canvas,
            Point point,
            int button
    ) {
        dispatch(
                canvas,
                MouseEvent.MOUSE_PRESSED,
                point,
                500,
                500,
                button,
                0
        );
        dispatch(
                canvas,
                MouseEvent.MOUSE_RELEASED,
                point,
                500,
                500,
                button,
                0
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

    private static BehaviorGraphVisualizationSnapshot snapshot() {
        return new BehaviorGraphVisualizationSnapshot(
                GRAPH_ID,
                "Test Ship",
                1L,
                1L,
                Instant.parse("2026-07-30T10:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(
                        new VisualizationNode(
                                NormalizedEventType.SYSTEM_ENTRY,
                                "System Entry",
                                0L
                        ),
                        new VisualizationNode(
                                NormalizedEventType.TOUCHDOWN,
                                "Touchdown",
                                0L
                        )
                ),
                List.of()
        );
    }

    private enum EmptyQuery
            implements BehaviorGraphVisualizationQuery {
        INSTANCE;

        @Override
        public Optional<GraphId> getActiveGraphId() {
            return Optional.empty();
        }

        @Override
        public Optional<BehaviorGraphVisualizationSnapshot>
                getVisualizationSnapshot(
                        GraphId graphId,
                        Instant evaluationTime
                ) {
            return Optional.empty();
        }

        @Override
        public ActiveEpisodeNodeOccurrencesSnapshot
                getActiveEpisodeOccurrences(
                        GraphId graphId,
                        NormalizedEventType eventType
                ) {
            return new ActiveEpisodeNodeOccurrencesSnapshot(
                    graphId,
                    Optional.empty(),
                    eventType,
                    new BehaviorGraphDisplayNameResolver().resolve(eventType),
                    0L,
                    0L,
                    List.of()
            );
        }

        @Override
        public Optional<EventOccurrenceDetailsSnapshot>
                getActiveEpisodeOccurrenceDetails(
                        GraphId graphId,
                        SystemEpisodeId episodeId,
                        EventOccurrenceId occurrenceId
                ) {
            return Optional.empty();
        }
    }

    private enum EmptyEventSource
            implements BehaviorGraphEventSource {
        INSTANCE;

        @Override
        public Subscription subscribe(BehaviorGraphListener listener) {
            Objects.requireNonNull(listener, "listener");
            return EmptySubscription.INSTANCE;
        }
    }

    private enum EmptySubscription
            implements BehaviorGraphEventSource.Subscription {
        INSTANCE;

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeDialogFactory
            implements BehaviorGraphOccurrenceDialogFactory {

        private int creationCount;
        private FakeDialog dialog;

        @Override
        public BehaviorGraphOccurrenceDialogHandle create(
                Window owner,
                BehaviorGraphOccurrenceInspector inspector,
                Runnable hiddenListener
        ) {
            creationCount++;
            dialog = new FakeDialog(inspector, hiddenListener);
            return dialog;
        }
    }

    private static final class FakeDialog
            implements BehaviorGraphOccurrenceDialogHandle {

        private final BehaviorGraphOccurrenceInspector inspector;
        private final Runnable hiddenListener;

        private int showCount;
        private boolean visible;
        private boolean disposed;

        private FakeDialog(
                BehaviorGraphOccurrenceInspector inspector,
                Runnable hiddenListener
        ) {
            this.inspector = inspector;
            this.hiddenListener = hiddenListener;
        }

        @Override
        public void showForExplicitSelection() {
            showCount++;
            visible = true;
        }

        @Override
        public void occurrencesApplied() {
        }

        @Override
        public boolean isVisible() {
            return visible;
        }

        @Override
        public void dispose() {
            disposed = true;
            visible = false;
        }

        private void close() {
            visible = false;
            hiddenListener.run();
        }
    }
}
