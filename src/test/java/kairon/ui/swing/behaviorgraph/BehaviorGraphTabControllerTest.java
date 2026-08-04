package kairon.ui.swing.behaviorgraph;

import kairon.behavior.event.BehaviorGraphEvent;
import kairon.behavior.event.BehaviorGraphEventSource;
import kairon.behavior.event.BehaviorGraphListener;
import kairon.behavior.graph.ActiveEpisodeNodeOccurrencesSnapshot;
import kairon.behavior.graph.BehaviorGraphDisplayNameResolver;
import kairon.behavior.graph.EventOccurrenceDetailsSnapshot;
import kairon.behavior.graph.BehaviorGraphVisualizationQuery;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot.VisualizationEdge;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot.VisualizationNode;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BehaviorGraphTabControllerTest {

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(2);
    private static final int TEST_DEBOUNCE_MILLIS = 40;
    private static final Instant NOW =
            Instant.parse("2026-07-30T06:00:00Z");
    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);
    private static final GraphId GRAPH_A =
            new GraphId("F1234567", 101L);
    private static final GraphId GRAPH_B =
            new GraphId("F1234567", 202L);

    private FakeQuery query;
    private FakeEventSource eventSource;
    private FakeView view;
    private ManualExecutorService snapshotLoader;
    private BehaviorGraphTabController controller;

    @BeforeEach
    void setUp() {
        query = new FakeQuery();
        eventSource = new FakeEventSource();
        view = new FakeView();
        snapshotLoader = new ManualExecutorService();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (controller != null) {
            runOnEdt(controller::dispose);
        }
        assertTrue(
                view.allCallsOnEdt(),
                "all view mutations must run on the EDT"
        );
    }

    @Test
    void behaviorGraphUpdatedRequestsRefresh() throws Exception {
        BehaviorGraphVisualizationSnapshot initial =
                snapshot(GRAPH_A, 1L, 1L, NormalizedEventType.SYSTEM_ENTRY);
        selectAndApply(initial);
        BehaviorGraphVisualizationSnapshot updated =
                snapshot(GRAPH_A, 2L, 1L, NormalizedEventType.SYSTEM_ENTRY);

        refreshAfter(
                new BehaviorGraphEvent.BehaviorGraphUpdated(GRAPH_A, NOW),
                updated
        );

        assertAll(
                () -> assertEquals(2, query.snapshotQueryCount()),
                () -> assertEquals(2, view.appliedSnapshots().size()),
                () -> assertSame(updated, view.lastSnapshot()),
                () -> assertFalse(onEdt(controller::isDirty))
        );
    }

    @Test
    void rapidUpdateEventsAreCoalescedIntoOneSnapshotQuery()
            throws Exception {
        selectAndApply(snapshot(
                GRAPH_A,
                1L,
                1L,
                NormalizedEventType.SYSTEM_ENTRY
        ));
        BehaviorGraphVisualizationSnapshot latest =
                snapshot(GRAPH_A, 7L, 1L, NormalizedEventType.DISEMBARK);
        query.respondWith(latest);

        for (int index = 0; index < 6; index++) {
            eventSource.emit(new BehaviorGraphEvent.BehaviorGraphUpdated(
                    GRAPH_A,
                    NOW.plusMillis(index)
            ));
        }
        flushEdt();
        snapshotLoader.runNextTask();
        flushEdt();

        assertAll(
                () -> assertEquals(2, query.snapshotQueryCount()),
                () -> assertEquals(0, snapshotLoader.queuedTaskCount()),
                () -> assertFalse(onEdt(controller::isRefreshPending)),
                () -> assertSame(latest, view.lastSnapshot())
        );
    }

    @Test
    void graphCursorChangedUpdatesCurrentNodeHighlight() throws Exception {
        selectAndApply(snapshot(
                GRAPH_A,
                1L,
                1L,
                NormalizedEventType.SYSTEM_ENTRY
        ));
        BehaviorGraphVisualizationSnapshot cursorSnapshot =
                snapshot(GRAPH_A, 2L, 1L, NormalizedEventType.DISEMBARK);
        query.respondWith(cursorSnapshot);
        GraphCursor cursor = new GraphCursor(
                GRAPH_A,
                new SystemEpisodeId("episode-cursor"),
                new EventOccurrenceId("occurrence-cursor"),
                NormalizedEventType.DISEMBARK,
                NOW
        );

        eventSource.emit(new BehaviorGraphEvent.GraphCursorChanged(
                GRAPH_A,
                Optional.of(cursor),
                NOW
        ));
        flushEdt();
        snapshotLoader.runNextTask();
        flushEdt();

        assertEquals(
                Optional.of(NormalizedEventType.DISEMBARK),
                view.lastSnapshot().currentEventType()
        );
    }

    @Test
    void hiddenTabBecomesDirtyWithoutRepeatedQueriesOrRepaints()
            throws Exception {
        selectAndApply(snapshot(
                GRAPH_A,
                1L,
                1L,
                NormalizedEventType.SYSTEM_ENTRY
        ));
        runOnEdt(() -> controller.setSelected(false));

        for (int index = 0; index < 10; index++) {
            eventSource.emit(new BehaviorGraphEvent.BehaviorGraphUpdated(
                    GRAPH_A,
                    NOW.plusMillis(index)
            ));
        }
        flushEdt();

        assertAll(
                () -> assertTrue(onEdt(controller::isDirty)),
                () -> assertFalse(onEdt(controller::isRefreshPending)),
                () -> assertEquals(1, query.snapshotQueryCount()),
                () -> assertEquals(1, view.appliedSnapshots().size()),
                () -> assertEquals(0, snapshotLoader.queuedTaskCount())
        );
    }

    @Test
    void visibleOccurrenceDialogKeepsRefreshActiveWithoutRecentering()
            throws Exception {
        selectAndApply(snapshot(
                GRAPH_A,
                1L,
                1L,
                NormalizedEventType.SYSTEM_ENTRY
        ));
        int centersBeforeDialogOnlyRefresh =
                view.centerRequestCount();
        runOnEdt(() -> {
            controller.setAuxiliaryViewActive(true);
            controller.setSelected(false);
        });

        BehaviorGraphVisualizationSnapshot updated =
                snapshot(
                        GRAPH_A,
                        2L,
                        1L,
                        NormalizedEventType.DISEMBARK
                );
        query.respondWith(updated);
        eventSource.emit(new BehaviorGraphEvent.ReplayCompleted(
                GRAPH_A,
                NOW
        ));
        flushEdt();
        snapshotLoader.runNextTask();
        flushEdt();

        assertAll(
                () -> assertSame(updated, view.lastSnapshot()),
                () -> assertEquals(
                        centersBeforeDialogOnlyRefresh,
                        view.centerRequestCount()
                ),
                () -> assertFalse(onEdt(controller::isDirty))
        );

        runOnEdt(() -> controller.setAuxiliaryViewActive(false));
        eventSource.emit(new BehaviorGraphEvent.ReplayCompleted(
                GRAPH_A,
                NOW.plusSeconds(1)
        ));
        flushEdt();
        assertAll(
                () -> assertTrue(onEdt(controller::isDirty)),
                () -> assertEquals(
                        0,
                        snapshotLoader.queuedTaskCount()
                ),
                () -> assertEquals(2, query.snapshotQueryCount())
        );
    }

    @Test
    void selectingDirtyTabLoadsLatestSnapshotAndCentersCurrentNode()
            throws Exception {
        selectAndApply(snapshot(
                GRAPH_A,
                1L,
                1L,
                NormalizedEventType.SYSTEM_ENTRY
        ));
        runOnEdt(() -> controller.setSelected(false));
        BehaviorGraphVisualizationSnapshot latest =
                snapshot(GRAPH_A, 8L, 2L, NormalizedEventType.DISEMBARK);
        query.respondWith(latest);
        eventSource.emit(new BehaviorGraphEvent.BehaviorGraphUpdated(
                GRAPH_A,
                NOW
        ));
        flushEdt();

        runOnEdt(() -> controller.setSelected(true));
        snapshotLoader.runNextTask();
        flushEdt();

        assertAll(
                () -> assertSame(latest, view.lastSnapshot()),
                () -> assertEquals(2, view.centerRequestCount()),
                () -> assertFalse(onEdt(controller::isDirty))
        );
    }

    @Test
    void visibleLiveUpdateDoesNotAutomaticallyRecenterViewport()
            throws Exception {
        selectAndApply(snapshot(
                GRAPH_A,
                1L,
                1L,
                NormalizedEventType.SYSTEM_ENTRY
        ));
        int centersAfterSelection = view.centerRequestCount();

        refreshAfter(
                new BehaviorGraphEvent.BehaviorGraphUpdated(GRAPH_A, NOW),
                snapshot(GRAPH_A, 2L, 1L, NormalizedEventType.DISEMBARK)
        );

        assertEquals(centersAfterSelection, view.centerRequestCount());
    }

    @Test
    void activeGraphChangeLoadsTheNewGraphIdAndRelayouts()
            throws Exception {
        selectAndApply(snapshot(
                GRAPH_A,
                1L,
                1L,
                NormalizedEventType.SYSTEM_ENTRY
        ));
        BehaviorGraphVisualizationSnapshot graphB =
                snapshot(GRAPH_B, 1L, 1L, NormalizedEventType.DISEMBARK);
        query.setActiveGraphId(Optional.of(GRAPH_B));
        query.respondWith(graphB);

        eventSource.emit(new BehaviorGraphEvent.ActiveGraphChanged(
                GRAPH_B,
                Optional.of(GRAPH_A),
                NOW
        ));
        flushEdt();
        snapshotLoader.runNextTask();
        flushEdt();

        assertAll(
                () -> assertEquals(
                        List.of(GRAPH_A, GRAPH_B),
                        query.requestedGraphIds()
                ),
                () -> assertSame(graphB, view.lastSnapshot()),
                () -> assertTrue(view.lastFullRelayout()),
                () -> assertEquals(2, view.centerRequestCount())
        );
    }

    @Test
    void staleBackgroundResultCannotOverwriteNewerRequest()
            throws Exception {
        ensureController(TEST_DEBOUNCE_MILLIS);
        BehaviorGraphVisualizationSnapshot stale =
                snapshot(GRAPH_A, 1L, 1L, NormalizedEventType.SYSTEM_ENTRY);
        BehaviorGraphVisualizationSnapshot newest =
                snapshot(GRAPH_A, 2L, 1L, NormalizedEventType.DISEMBARK);
        CountDownLatch firstQueryEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstQuery = new CountDownLatch(1);
        query.setActiveGraphId(Optional.of(GRAPH_A));
        query.respondWithBlocking(
                stale,
                firstQueryEntered,
                releaseFirstQuery
        );

        runOnEdt(() -> controller.setSelected(true));
        Runnable firstTask = snapshotLoader.awaitNextTask();
        Thread firstLoader = Thread.ofPlatform()
                .name("controller-test-stale-loader")
                .start(firstTask);
        assertTrue(firstQueryEntered.await(
                ASYNC_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS
        ));

        query.respondWith(newest);
        eventSource.emit(new BehaviorGraphEvent.ReplayCompleted(
                GRAPH_A,
                NOW
        ));
        flushEdt();
        assertEquals(1, snapshotLoader.queuedTaskCount());

        releaseFirstQuery.countDown();
        firstLoader.join(ASYNC_TIMEOUT.toMillis());
        assertFalse(firstLoader.isAlive());
        snapshotLoader.runNextTask();
        flushEdt();

        assertAll(
                () -> assertEquals(2, query.snapshotQueryCount()),
                () -> assertEquals(List.of(newest), view.appliedSnapshots()),
                () -> assertSame(newest, view.lastSnapshot())
        );
    }

    @Test
    void lowerGraphVersionCannotReplaceAnAppliedSnapshot()
            throws Exception {
        BehaviorGraphVisualizationSnapshot newest =
                snapshot(GRAPH_A, 5L, 2L, NormalizedEventType.DISEMBARK);
        selectAndApply(newest);

        refreshAfter(
                new BehaviorGraphEvent.BehaviorGraphUpdated(GRAPH_A, NOW),
                snapshot(
                        GRAPH_A,
                        4L,
                        2L,
                        NormalizedEventType.SYSTEM_ENTRY
                )
        );

        assertAll(
                () -> assertEquals(1, view.appliedSnapshots().size()),
                () -> assertSame(newest, view.lastSnapshot()),
                () -> assertTrue(onEdt(controller::isDirty))
        );
    }

    @Test
    void topologyChangesRelayoutWhileCountAndWeightChangesReuseLayout()
            throws Exception {
        selectAndApply(snapshot(
                GRAPH_A,
                1L,
                1L,
                NormalizedEventType.SYSTEM_ENTRY
        ));

        refreshAfter(
                new BehaviorGraphEvent.BehaviorGraphUpdated(GRAPH_A, NOW),
                snapshot(GRAPH_A, 2L, 2L, NormalizedEventType.DISEMBARK)
        );
        refreshAfter(
                new BehaviorGraphEvent.BehaviorGraphUpdated(
                        GRAPH_A,
                        NOW.plusSeconds(1)
                ),
                snapshot(GRAPH_A, 3L, 2L, NormalizedEventType.DISEMBARK)
        );

        assertEquals(
                List.of(true, true, false),
                view.fullRelayoutRequests()
        );
        assertEquals(
                3,
                view.lastSnapshot().nodes().getFirst()
                        .activeEpisodeOccurrenceCount()
        );
    }

    @Test
    void replayCompletedForcesFinalRefreshAndCancelsDebounce()
            throws Exception {
        selectAndApply(snapshot(
                GRAPH_A,
                1L,
                1L,
                NormalizedEventType.SYSTEM_ENTRY
        ));
        BehaviorGraphVisualizationSnapshot replayFinal =
                snapshot(GRAPH_A, 9L, 3L, NormalizedEventType.DISEMBARK);
        query.respondWith(replayFinal);

        runOnEdt(() -> {
            eventSource.emit(new BehaviorGraphEvent.BehaviorGraphUpdated(
                    GRAPH_A,
                    NOW
            ));
            eventSource.emit(new BehaviorGraphEvent.ReplayCompleted(
                    GRAPH_A,
                    NOW.plusMillis(1)
            ));
        });
        flushEdt();

        assertAll(
                () -> assertEquals(1, snapshotLoader.queuedTaskCount()),
                () -> assertFalse(onEdt(controller::isRefreshPending))
        );
        snapshotLoader.runNextTask();
        flushEdt();

        assertAll(
                () -> assertEquals(2, query.snapshotQueryCount()),
                () -> assertSame(replayFinal, view.lastSnapshot())
        );
    }

    @Test
    void disposeRemovesSubscriptionStopsTimerAndShutsDownExecutor()
            throws Exception {
        ensureController(5_000);
        selectAndApply(snapshot(
                GRAPH_A,
                1L,
                1L,
                NormalizedEventType.SYSTEM_ENTRY
        ));
        eventSource.emit(new BehaviorGraphEvent.BehaviorGraphUpdated(
                GRAPH_A,
                NOW
        ));
        flushEdt();
        assertTrue(onEdt(controller::isRefreshPending));
        int queryCountBeforeDispose = query.snapshotQueryCount();

        runOnEdt(controller::dispose);

        assertAll(
                () -> assertFalse(eventSource.isSubscribed()),
                () -> assertEquals(1, eventSource.closeCount()),
                () -> assertFalse(onEdt(controller::isRefreshPending)),
                () -> assertTrue(snapshotLoader.shutdownNowCalled()),
                () -> assertTrue(snapshotLoader.isShutdown()),
                () -> assertEquals(0, snapshotLoader.queuedTaskCount())
        );

        eventSource.emit(new BehaviorGraphEvent.ReplayCompleted(
                GRAPH_A,
                NOW
        ));
        flushEdt();
        assertEquals(queryCountBeforeDispose, query.snapshotQueryCount());
    }

    @Test
    void refreshFailurePreservesLastValidGraph() throws Exception {
        BehaviorGraphVisualizationSnapshot valid =
                snapshot(GRAPH_A, 3L, 2L, NormalizedEventType.DISEMBARK);
        selectAndApply(valid);
        RuntimeException expectedFailure =
                new IllegalStateException("snapshot failure");
        expectedFailure.setStackTrace(new StackTraceElement[0]);
        query.failWith(expectedFailure);

        eventSource.emit(new BehaviorGraphEvent.BehaviorGraphUpdated(
                GRAPH_A,
                NOW
        ));
        flushEdt();
        snapshotLoader.runNextTask();
        flushEdt();

        assertAll(
                () -> assertEquals(1, view.refreshErrorCount()),
                () -> assertEquals(1, view.appliedSnapshots().size()),
                () -> assertSame(valid, view.lastSnapshot()),
                () -> assertTrue(view.hasGraphDataValue()),
                () -> assertTrue(onEdt(controller::isDirty))
        );
    }

    private void selectAndApply(
            BehaviorGraphVisualizationSnapshot snapshot
    ) throws Exception {
        ensureController(TEST_DEBOUNCE_MILLIS);
        query.setActiveGraphId(Optional.of(snapshot.graphId()));
        query.respondWith(snapshot);
        runOnEdt(() -> controller.setSelected(true));
        snapshotLoader.runNextTask();
        flushEdt();
    }

    private void refreshAfter(
            BehaviorGraphEvent event,
            BehaviorGraphVisualizationSnapshot snapshot
    ) throws Exception {
        query.respondWith(snapshot);
        eventSource.emit(event);
        flushEdt();
        snapshotLoader.runNextTask();
        flushEdt();
    }

    private void ensureController(int debounceMillis) throws Exception {
        if (controller != null) {
            return;
        }
        controller = onEdt(() -> new BehaviorGraphTabController(
                query,
                eventSource,
                view,
                snapshotLoader,
                CLOCK,
                debounceMillis
        ));
    }

    private static BehaviorGraphVisualizationSnapshot snapshot(
            GraphId graphId,
            long graphVersion,
            long topologyVersion,
            NormalizedEventType currentEventType
    ) {
        List<VisualizationNode> nodes = List.of(
                new VisualizationNode(
                        NormalizedEventType.SYSTEM_ENTRY,
                        "System Entry",
                        Math.max(1L, graphVersion)
                ),
                new VisualizationNode(
                        NormalizedEventType.DISEMBARK,
                        "Disembark",
                        Math.max(1L, graphVersion)
                )
        );
        List<VisualizationEdge> edges = List.of(new VisualizationEdge(
                NormalizedEventType.SYSTEM_ENTRY,
                NormalizedEventType.DISEMBARK,
                Math.max(1L, graphVersion),
                Math.max(0.1, graphVersion)
        ));
        return new BehaviorGraphVisualizationSnapshot(
                graphId,
                "Test Ship " + graphId.shipId(),
                graphVersion,
                topologyVersion,
                NOW,
                Optional.of(currentEventType),
                Optional.of(new EventOccurrenceId(
                        "occurrence-" + graphVersion
                )),
                Optional.of(new SystemEpisodeId(
                        "episode-" + graphVersion
                )),
                nodes,
                edges
        );
    }

    private static void runOnEdt(Runnable action)
            throws Exception {
        Objects.requireNonNull(action, "action");
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        SwingUtilities.invokeAndWait(action);
    }

    private static <T> T onEdt(Callable<T> action)
            throws Exception {
        Objects.requireNonNull(action, "action");
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        try {
            return task.get();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) {
                throw exception;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    private static void flushEdt() throws Exception {
        runOnEdt(() -> {
        });
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(
                    ASYNC_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
            )) {
                throw new IllegalStateException("timed out waiting for latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while waiting for latch",
                    interrupted
            );
        }
    }

    @FunctionalInterface
    private interface SnapshotHandler {

        Optional<BehaviorGraphVisualizationSnapshot> load(
                GraphId graphId,
                Instant evaluationTime
        );
    }

    private static final class FakeQuery
            implements BehaviorGraphVisualizationQuery {

        private final AtomicInteger snapshotQueryCount = new AtomicInteger();
        private final List<GraphId> requestedGraphIds =
                Collections.synchronizedList(new ArrayList<>());
        private volatile Optional<GraphId> activeGraphId =
                Optional.of(GRAPH_A);
        private volatile SnapshotHandler handler =
                (graphId, evaluationTime) -> Optional.empty();

        @Override
        public Optional<GraphId> getActiveGraphId() {
            requireBackgroundThread();
            return activeGraphId;
        }

        @Override
        public Optional<BehaviorGraphVisualizationSnapshot>
                getVisualizationSnapshot(
                        GraphId graphId,
                        Instant evaluationTime
                ) {
            requireBackgroundThread();
            snapshotQueryCount.incrementAndGet();
            requestedGraphIds.add(graphId);
            return handler.load(graphId, evaluationTime);
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

        void setActiveGraphId(Optional<GraphId> graphId) {
            activeGraphId = Objects.requireNonNull(graphId, "graphId");
        }

        void respondWith(BehaviorGraphVisualizationSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            handler = (graphId, evaluationTime) -> Optional.of(snapshot);
        }

        void respondWithBlocking(
                BehaviorGraphVisualizationSnapshot snapshot,
                CountDownLatch entered,
                CountDownLatch release
        ) {
            Objects.requireNonNull(snapshot, "snapshot");
            handler = (graphId, evaluationTime) -> {
                entered.countDown();
                awaitLatch(release);
                return Optional.of(snapshot);
            };
        }

        void failWith(RuntimeException failure) {
            Objects.requireNonNull(failure, "failure");
            handler = (graphId, evaluationTime) -> {
                throw failure;
            };
        }

        int snapshotQueryCount() {
            return snapshotQueryCount.get();
        }

        List<GraphId> requestedGraphIds() {
            synchronized (requestedGraphIds) {
                return List.copyOf(requestedGraphIds);
            }
        }

        private static void requireBackgroundThread() {
            if (SwingUtilities.isEventDispatchThread()) {
                throw new AssertionError(
                        "visualization query must not run on the EDT"
                );
            }
        }
    }

    private static final class FakeEventSource
            implements BehaviorGraphEventSource {

        private volatile BehaviorGraphListener listener;
        private final AtomicBoolean subscribed = new AtomicBoolean();
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public Subscription subscribe(BehaviorGraphListener newListener) {
            listener = Objects.requireNonNull(newListener, "newListener");
            if (!subscribed.compareAndSet(false, true)) {
                throw new IllegalStateException("already subscribed");
            }
            return new Subscription() {
                @Override
                public boolean isActive() {
                    return subscribed.get();
                }

                @Override
                public void close() {
                    if (subscribed.compareAndSet(true, false)) {
                        closeCount.incrementAndGet();
                    }
                }
            };
        }

        void emit(BehaviorGraphEvent event) {
            BehaviorGraphListener current = listener;
            if (subscribed.get() && current != null) {
                current.onBehaviorGraphEvent(event);
            }
        }

        boolean isSubscribed() {
            return subscribed.get();
        }

        int closeCount() {
            return closeCount.get();
        }
    }

    private static final class FakeView
            implements BehaviorGraphTabController.View {

        private final List<BehaviorGraphVisualizationSnapshot>
                appliedSnapshots = new ArrayList<>();
        private final List<Boolean> fullRelayoutRequests = new ArrayList<>();
        private boolean graphData;
        private boolean allCallsOnEdt = true;
        private int centerRequestCount;
        private int refreshErrorCount;

        @Override
        public boolean applySnapshot(
                BehaviorGraphVisualizationSnapshot snapshot,
                boolean fullRelayout
        ) {
            recordThread();
            appliedSnapshots.add(snapshot);
            fullRelayoutRequests.add(fullRelayout);
            graphData = !snapshot.nodes().isEmpty();
            return fullRelayout;
        }

        @Override
        public void showNoActiveShip() {
            recordThread();
            graphData = false;
        }

        @Override
        public void showEmptyGraph() {
            recordThread();
            graphData = false;
        }

        @Override
        public void showRefreshError() {
            recordThread();
            refreshErrorCount++;
        }

        @Override
        public boolean hasGraphData() {
            recordThread();
            return graphData;
        }

        @Override
        public void centerCurrentNodeLater() {
            recordThread();
            centerRequestCount++;
        }

        List<BehaviorGraphVisualizationSnapshot> appliedSnapshots() {
            return List.copyOf(appliedSnapshots);
        }

        BehaviorGraphVisualizationSnapshot lastSnapshot() {
            assertFalse(appliedSnapshots.isEmpty());
            return appliedSnapshots.getLast();
        }

        List<Boolean> fullRelayoutRequests() {
            return List.copyOf(fullRelayoutRequests);
        }

        boolean lastFullRelayout() {
            assertFalse(fullRelayoutRequests.isEmpty());
            return fullRelayoutRequests.getLast();
        }

        boolean hasGraphDataValue() {
            return graphData;
        }

        int centerRequestCount() {
            return centerRequestCount;
        }

        int refreshErrorCount() {
            return refreshErrorCount;
        }

        boolean allCallsOnEdt() {
            return allCallsOnEdt;
        }

        private void recordThread() {
            allCallsOnEdt &= SwingUtilities.isEventDispatchThread();
        }
    }

    private static final class ManualExecutorService
            extends AbstractExecutorService {

        private final BlockingQueue<Runnable> tasks =
                new LinkedBlockingQueue<>();
        private final AtomicBoolean shutdown = new AtomicBoolean();
        private final AtomicBoolean shutdownNowCalled = new AtomicBoolean();

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdownNowCalled.set(true);
            shutdown.set(true);
            List<Runnable> pending = new ArrayList<>();
            tasks.drainTo(pending);
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return shutdown.get() && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (!isTerminated() && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command, "command");
            if (shutdown.get()) {
                throw new RejectedExecutionException("executor is shut down");
            }
            tasks.add(command);
        }

        void runNextTask()
                throws InterruptedException, TimeoutException {
            awaitNextTask().run();
        }

        Runnable awaitNextTask()
                throws InterruptedException, TimeoutException {
            Runnable task = tasks.poll(
                    ASYNC_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            if (task == null) {
                throw new TimeoutException(
                        "timed out waiting for snapshot task"
                );
            }
            return task;
        }

        int queuedTaskCount() {
            return tasks.size();
        }

        boolean shutdownNowCalled() {
            return shutdownNowCalled.get();
        }
    }
}
