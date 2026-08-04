package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.ActiveEpisodeNodeOccurrencesSnapshot;
import kairon.behavior.graph.BehaviorGraphOccurrenceQuery;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationNode;
import kairon.behavior.graph.EventOccurrenceDetailsSnapshot;
import kairon.behavior.graph.EventOccurrenceSummary;
import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BehaviorGraphOccurrenceControllerTest {

    private static final GraphId GRAPH_A = new GraphId("F100", 9L);
    private static final GraphId GRAPH_B = new GraphId("F100", 14L);
    private static final SystemEpisodeId EPISODE_A =
            new SystemEpisodeId("episode-a");
    private static final SystemEpisodeId EPISODE_B =
            new SystemEpisodeId("episode-b");
    private static final Instant NOW =
            Instant.parse("2026-07-30T09:00:00Z");

    @Test
    void firstSnapshotSelectsCurrentAndCursorMovementDoesNotFollow()
            throws Exception {
        Harness harness = new Harness();
        harness.query.respond(
                occurrences(
                        GRAPH_A,
                        EPISODE_A,
                        NormalizedEventType.TOUCHDOWN,
                        1L,
                        rows("touch", 2)
                )
        );
        harness.query.respondDetails(details(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                "touch-2"
        ));

        harness.apply(snapshot(
                GRAPH_A,
                EPISODE_A,
                1L,
                NormalizedEventType.TOUCHDOWN,
                2L,
                0L
        ));
        assertEquals(
                Optional.of(NormalizedEventType.TOUCHDOWN),
                harness.view.selectedNode
        );

        harness.runNext();
        assertEquals(2, harness.view.rows.size());
        assertEquals(
                Optional.of(new EventOccurrenceId("touch-2")),
                harness.view.selectedOccurrence
        );
        harness.runNext();
        assertEquals(
                new EventOccurrenceId("touch-2"),
                harness.view.details.orElseThrow().occurrenceId()
        );

        harness.apply(snapshot(
                GRAPH_A,
                EPISODE_A,
                2L,
                NormalizedEventType.SAA_SIGNALS_FOUND,
                2L,
                1L
        ));
        assertEquals(
                Optional.of(NormalizedEventType.TOUCHDOWN),
                harness.view.selectedNode
        );
        assertEquals(0, harness.executor.queuedTaskCount());
        assertTrue(harness.view.allCallsOnEdt);
        harness.dispose();
    }

    @Test
    void missingCurrentFallsBackToSystemEntry() throws Exception {
        Harness harness = new Harness();
        harness.query.respond(new ActiveEpisodeNodeOccurrencesSnapshot(
                GRAPH_A,
                Optional.empty(),
                NormalizedEventType.SYSTEM_ENTRY,
                "System Entry",
                1L,
                0L,
                List.of()
        ));

        harness.apply(snapshotWithoutCurrent(GRAPH_A, 1L));

        assertEquals(
                Optional.of(NormalizedEventType.SYSTEM_ENTRY),
                harness.view.selectedNode
        );
        harness.runNext();
        assertEquals(
                BehaviorGraphOccurrenceInspector.NO_ACTIVE_EPISODE_MESSAGE,
                harness.view.message
        );
        harness.dispose();
    }

    @Test
    void rapidNodeAndGraphChangesDiscardOldResults() throws Exception {
        Harness harness = new Harness();
        harness.query.respond(occurrences(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.SYSTEM_ENTRY,
                1L,
                rows("root-a", 1)
        ));
        harness.query.respond(occurrences(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                1L,
                rows("touch-a", 1)
        ));

        harness.apply(snapshot(
                GRAPH_A,
                EPISODE_A,
                1L,
                NormalizedEventType.SYSTEM_ENTRY,
                1L,
                1L
        ));
        harness.selectNode(NormalizedEventType.TOUCHDOWN);
        harness.runNext();
        assertTrue(harness.view.rows.isEmpty());
        harness.runNext();
        assertEquals(
                List.of("touch-a-1"),
                occurrenceIds(harness.view.rows)
        );

        harness.query.respond(occurrences(
                GRAPH_B,
                EPISODE_B,
                NormalizedEventType.TOUCHDOWN,
                1L,
                rows("touch-b", 1)
        ));
        harness.selectNode(NormalizedEventType.SYSTEM_ENTRY);
        harness.apply(snapshot(
                GRAPH_B,
                EPISODE_B,
                1L,
                NormalizedEventType.TOUCHDOWN,
                1L,
                0L
        ));
        assertTrue(harness.view.rows.isEmpty());
        harness.runNext();
        assertTrue(harness.view.rows.isEmpty());
        harness.runNext();
        assertTrue(harness.view.rows.isEmpty());
        harness.runNext();
        assertEquals(
                List.of("touch-b-1"),
                occurrenceIds(harness.view.rows)
        );
        assertEquals(
                Optional.of(NormalizedEventType.TOUCHDOWN),
                harness.view.selectedNode
        );
        harness.dispose();
    }

    @Test
    void newEpisodeRetainsTypeButImmediatelyClearsOldRows()
            throws Exception {
        Harness harness = new Harness();
        harness.query.respond(occurrences(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                1L,
                rows("old", 2)
        ));
        harness.apply(snapshot(
                GRAPH_A,
                EPISODE_A,
                1L,
                NormalizedEventType.TOUCHDOWN,
                2L,
                0L
        ));
        harness.runNext();
        assertEquals(2, harness.view.rows.size());

        harness.query.respond(occurrences(
                GRAPH_A,
                EPISODE_B,
                NormalizedEventType.TOUCHDOWN,
                2L,
                List.of()
        ));
        harness.apply(snapshot(
                GRAPH_A,
                EPISODE_B,
                2L,
                NormalizedEventType.SYSTEM_ENTRY,
                0L,
                1L
        ));

        assertEquals(
                Optional.of(NormalizedEventType.TOUCHDOWN),
                harness.view.selectedNode
        );
        assertTrue(harness.view.rows.isEmpty());
        assertTrue(harness.view.selectedOccurrence.isEmpty());
        harness.runNext();
        assertTrue(harness.view.rows.isEmpty());
        harness.runNext();
        assertTrue(harness.view.rows.isEmpty());
        assertEquals(
                BehaviorGraphOccurrenceInspector.NO_OCCURRENCES_MESSAGE,
                harness.view.message
        );
        harness.dispose();
    }

    @Test
    void liveRefreshPreservesOccurrenceAndSkipsOtherTypeReload()
            throws Exception {
        Harness harness = new Harness();
        harness.query.respond(occurrences(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                1L,
                rows("touch", 2)
        ));
        harness.query.respondDetails(details(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                "touch-2"
        ));
        harness.apply(snapshot(
                GRAPH_A,
                EPISODE_A,
                1L,
                NormalizedEventType.TOUCHDOWN,
                2L,
                0L
        ));
        harness.runNext();
        harness.runNext();
        assertEquals(1, harness.query.listQueryCount);

        harness.selectOccurrence("touch-1");
        harness.query.respondDetails(details(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                "touch-1"
        ));
        harness.runNext();
        harness.apply(snapshot(
                GRAPH_A,
                EPISODE_A,
                2L,
                NormalizedEventType.SAA_SIGNALS_FOUND,
                2L,
                1L
        ));
        assertEquals(1, harness.query.listQueryCount);
        assertEquals(0, harness.executor.queuedTaskCount());

        harness.query.respond(occurrences(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                3L,
                rows("touch", 3)
        ));
        harness.apply(snapshot(
                GRAPH_A,
                EPISODE_A,
                3L,
                NormalizedEventType.SAA_SIGNALS_FOUND,
                3L,
                1L
        ));
        harness.runNext();
        assertEquals(
                Optional.of(new EventOccurrenceId("touch-1")),
                harness.view.selectedOccurrence
        );
        assertEquals(3, harness.view.rows.size());
        assertEquals(2, harness.query.listQueryCount);
        assertEquals(0, harness.executor.queuedTaskCount());
        harness.dispose();
    }

    @Test
    void staleDetailsFailureAndDisposalAreHandledSafely()
            throws Exception {
        Harness harness = new Harness();
        harness.query.respond(occurrences(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                1L,
                rows("touch", 2)
        ));
        harness.query.respondDetails(details(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                "touch-2"
        ));
        harness.apply(snapshot(
                GRAPH_A,
                EPISODE_A,
                1L,
                NormalizedEventType.TOUCHDOWN,
                2L,
                0L
        ));
        harness.runNext();
        harness.runNext();

        harness.query.respondDetails(details(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                "touch-1"
        ));
        harness.selectOccurrence("touch-1");
        harness.query.respondDetails(details(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                "touch-2"
        ));
        harness.selectOccurrence("touch-2");
        harness.runNext();
        assertTrue(harness.view.details.isEmpty());
        harness.runNext();
        assertEquals(
                "touch-2",
                harness.view.details.orElseThrow()
                        .occurrenceId().value()
        );

        List<EventOccurrenceSummary> preservedRows =
                List.copyOf(harness.view.rows);
        harness.query.failLists = true;
        harness.apply(snapshot(
                GRAPH_A,
                EPISODE_A,
                2L,
                NormalizedEventType.TOUCHDOWN,
                3L,
                0L
        ));
        harness.runNext();
        assertEquals(preservedRows, harness.view.rows);
        assertEquals(1, harness.view.loadErrorCount);

        harness.dispose();
        assertTrue(harness.executor.shutdownNowCalled);
        assertTrue(harness.view.allCallsOnEdt);
    }

    @Test
    void hiddenInspectorRejectsPendingResultsAndReloadsLatestState()
            throws Exception {
        Harness harness = new Harness();
        harness.query.respond(occurrences(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                1L,
                rows("old", 1)
        ));
        harness.apply(snapshot(
                GRAPH_A,
                EPISODE_A,
                1L,
                NormalizedEventType.TOUCHDOWN,
                1L,
                0L
        ));

        harness.setInspectorVisible(false);
        harness.runNext();
        assertTrue(harness.view.rows.isEmpty());
        assertFalse(harness.inspectorVisible());

        harness.query.respond(occurrences(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                2L,
                rows("latest", 2)
        ));
        harness.apply(snapshot(
                GRAPH_A,
                EPISODE_A,
                2L,
                NormalizedEventType.TOUCHDOWN,
                2L,
                0L
        ));
        assertEquals(0, harness.executor.queuedTaskCount());

        harness.setInspectorVisible(true);
        assertEquals(1, harness.executor.queuedTaskCount());
        harness.runNext();
        assertEquals(
                List.of("latest-1", "latest-2"),
                occurrenceIds(harness.view.rows)
        );
        assertTrue(harness.inspectorVisible());
        assertTrue(harness.view.allCallsOnEdt);
        harness.dispose();
    }

    @Test
    void hidingInspectorRejectsPendingOccurrenceDetails()
            throws Exception {
        Harness harness = new Harness();
        harness.query.respond(occurrences(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                1L,
                rows("touch", 1)
        ));
        harness.query.respondDetails(details(
                GRAPH_A,
                EPISODE_A,
                NormalizedEventType.TOUCHDOWN,
                "touch-1"
        ));
        harness.apply(snapshot(
                GRAPH_A,
                EPISODE_A,
                1L,
                NormalizedEventType.TOUCHDOWN,
                1L,
                0L
        ));
        harness.runNext();
        assertEquals(1, harness.executor.queuedTaskCount());

        harness.setInspectorVisible(false);
        harness.runNext();
        assertTrue(harness.view.details.isEmpty());
        assertFalse(harness.inspectorVisible());
        assertTrue(harness.view.allCallsOnEdt);
        harness.dispose();
    }

    private static ActiveEpisodeNodeOccurrencesSnapshot occurrences(
            GraphId graphId,
            SystemEpisodeId episodeId,
            NormalizedEventType eventType,
            long graphVersion,
            List<EventOccurrenceSummary> rows
    ) {
        return new ActiveEpisodeNodeOccurrencesSnapshot(
                graphId,
                Optional.of(episodeId),
                eventType,
                displayName(eventType),
                graphVersion,
                rows.size() + 1L,
                rows
        );
    }

    private static List<EventOccurrenceSummary> rows(
            String prefix,
            int count
    ) {
        List<EventOccurrenceSummary> rows = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            rows.add(new EventOccurrenceSummary(
                    new EventOccurrenceId(prefix + '-' + index),
                    NOW.plusSeconds(index),
                    index,
                    100L + index,
                    prefix.startsWith("touch")
                            ? "Touchdown"
                            : "FSDJump"
            ));
        }
        return List.copyOf(rows);
    }

    private static EventOccurrenceDetailsSnapshot details(
            GraphId graphId,
            SystemEpisodeId episodeId,
            NormalizedEventType eventType,
            String occurrenceId
    ) {
        return new EventOccurrenceDetailsSnapshot(
                graphId,
                episodeId,
                new EventOccurrenceId(occurrenceId),
                eventType,
                displayName(eventType).replace(" ", ""),
                NOW,
                1L,
                101L,
                "test-source",
                Map.of(),
                emptyContext()
        );
    }

    private static BehaviorGraphVisualizationSnapshot snapshot(
            GraphId graphId,
            SystemEpisodeId episodeId,
            long graphVersion,
            NormalizedEventType current,
            long touchdownCount,
            long signalsCount
    ) {
        return new BehaviorGraphVisualizationSnapshot(
                graphId,
                "Test Ship",
                graphVersion,
                1L,
                NOW,
                Optional.of(current),
                Optional.of(new EventOccurrenceId(
                        "cursor-" + graphVersion
                )),
                Optional.of(episodeId),
                List.of(
                        new VisualizationNode(
                                NormalizedEventType.SYSTEM_ENTRY,
                                "System Entry",
                                1L
                        ),
                        new VisualizationNode(
                                NormalizedEventType.SAA_SIGNALS_FOUND,
                                "SAA Signals Found",
                                signalsCount
                        ),
                        new VisualizationNode(
                                NormalizedEventType.TOUCHDOWN,
                                "Touchdown",
                                touchdownCount
                        )
                ),
                List.of()
        );
    }

    private static BehaviorGraphVisualizationSnapshot snapshotWithoutCurrent(
            GraphId graphId,
            long graphVersion
    ) {
        return new BehaviorGraphVisualizationSnapshot(
                graphId,
                "Test Ship",
                graphVersion,
                1L,
                NOW,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(new VisualizationNode(
                        NormalizedEventType.SYSTEM_ENTRY,
                        "System Entry",
                        0L
                )),
                List.of()
        );
    }

    private static String displayName(NormalizedEventType eventType) {
        if (eventType.equals(NormalizedEventType.SYSTEM_ENTRY)) {
            return "System Entry";
        }
        if (eventType.equals(NormalizedEventType.TOUCHDOWN)) {
            return "Touchdown";
        }
        return "SAA Signals Found";
    }

    private static List<String> occurrenceIds(
            List<EventOccurrenceSummary> rows
    ) {
        return rows.stream()
                .map(row -> row.occurrenceId().value())
                .toList();
    }

    private static ContextSnapshot emptyContext() {
        return new ContextSnapshot(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static final class Harness {

        private final FakeQuery query = new FakeQuery();
        private final FakeView view = new FakeView();
        private final ManualExecutor executor = new ManualExecutor();
        private BehaviorGraphOccurrenceController controller;

        private Harness() throws Exception {
            SwingUtilities.invokeAndWait(() ->
                    controller = new BehaviorGraphOccurrenceController(
                            query,
                            view,
                            executor
                    ));
        }

        private void apply(
                BehaviorGraphVisualizationSnapshot snapshot
        ) throws Exception {
            SwingUtilities.invokeAndWait(() ->
                    controller.onGraphSnapshotApplied(snapshot));
        }

        private void selectNode(NormalizedEventType eventType)
                throws Exception {
            SwingUtilities.invokeAndWait(() ->
                    controller.selectNode(eventType));
        }

        private void selectOccurrence(String occurrenceId)
                throws Exception {
            SwingUtilities.invokeAndWait(() ->
                    controller.selectOccurrence(Optional.of(
                            new EventOccurrenceId(occurrenceId)
                    )));
        }

        private void setInspectorVisible(boolean visible)
                throws Exception {
            SwingUtilities.invokeAndWait(() ->
                    controller.setInspectorVisible(visible));
        }

        private boolean inspectorVisible() throws Exception {
            AtomicBoolean visible = new AtomicBoolean();
            SwingUtilities.invokeAndWait(() ->
                    visible.set(controller.isInspectorVisible()));
            return visible.get();
        }

        private void runNext() throws Exception {
            executor.runNext();
            SwingUtilities.invokeAndWait(() -> {
            });
        }

        private void dispose() throws Exception {
            SwingUtilities.invokeAndWait(controller::dispose);
        }
    }

    private static final class FakeQuery
            implements BehaviorGraphOccurrenceQuery {

        private final Map<QueryKey, ActiveEpisodeNodeOccurrencesSnapshot>
                occurrenceResponses = new HashMap<>();
        private final Map<EventOccurrenceId, EventOccurrenceDetailsSnapshot>
                detailResponses = new HashMap<>();
        private int listQueryCount;
        private boolean failLists;

        @Override
        public ActiveEpisodeNodeOccurrencesSnapshot
                getActiveEpisodeOccurrences(
                        GraphId graphId,
                        NormalizedEventType eventType
                ) {
            assertFalse(SwingUtilities.isEventDispatchThread());
            listQueryCount++;
            if (failLists) {
                throw new IllegalStateException("list failure");
            }
            return Objects.requireNonNull(
                    occurrenceResponses.get(
                            new QueryKey(graphId, eventType)
                    ),
                    "missing occurrence response"
            );
        }

        @Override
        public Optional<EventOccurrenceDetailsSnapshot>
                getActiveEpisodeOccurrenceDetails(
                        GraphId graphId,
                        SystemEpisodeId episodeId,
                        EventOccurrenceId occurrenceId
                ) {
            assertFalse(SwingUtilities.isEventDispatchThread());
            return Optional.ofNullable(
                    detailResponses.get(occurrenceId)
            );
        }

        private void respond(
                ActiveEpisodeNodeOccurrencesSnapshot snapshot
        ) {
            occurrenceResponses.put(
                    new QueryKey(snapshot.graphId(), snapshot.eventType()),
                    snapshot
            );
        }

        private void respondDetails(
                EventOccurrenceDetailsSnapshot details
        ) {
            detailResponses.put(details.occurrenceId(), details);
        }
    }

    private record QueryKey(
            GraphId graphId,
            NormalizedEventType eventType
    ) {
    }

    private static final class FakeView
            implements BehaviorGraphOccurrenceController.View {

        private Optional<NormalizedEventType> selectedNode =
                Optional.empty();
        private Optional<EventOccurrenceId> selectedOccurrence =
                Optional.empty();
        private Optional<EventOccurrenceDetailsSnapshot> details =
                Optional.empty();
        private List<EventOccurrenceSummary> rows = List.of();
        private String message;
        private int loadErrorCount;
        private boolean allCallsOnEdt = true;

        @Override
        public void setSelectedNode(
                Optional<NormalizedEventType> eventType
        ) {
            recordThread();
            selectedNode = eventType;
        }

        @Override
        public void makeNodeVisible(NormalizedEventType eventType) {
            recordThread();
        }

        @Override
        public void showInitial() {
            recordThread();
            rows = List.of();
            selectedOccurrence = Optional.empty();
            details = Optional.empty();
            message = BehaviorGraphOccurrenceInspector.INITIAL_MESSAGE;
        }

        @Override
        public void showSelectedNodePending(
                String displayName,
                long activeOccurrenceCount,
                boolean activeEpisodePresent
        ) {
            recordThread();
            rows = List.of();
            selectedOccurrence = Optional.empty();
            details = Optional.empty();
            if (!activeEpisodePresent) {
                message = BehaviorGraphOccurrenceInspector
                        .NO_ACTIVE_EPISODE_MESSAGE;
            } else if (activeOccurrenceCount == 0L) {
                message = BehaviorGraphOccurrenceInspector
                        .NO_OCCURRENCES_MESSAGE;
            } else {
                message = BehaviorGraphOccurrenceInspector.LOADING_MESSAGE;
            }
        }

        @Override
        public Optional<EventOccurrenceId> applyOccurrences(
                ActiveEpisodeNodeOccurrencesSnapshot snapshot,
                Optional<EventOccurrenceId> occurrenceToPreserve,
                boolean selectLatest
        ) {
            recordThread();
            rows = snapshot.occurrences();
            Optional<EventOccurrenceId> preserved =
                    occurrenceToPreserve.filter(id -> rows.stream()
                            .anyMatch(row ->
                                    row.occurrenceId().equals(id)));
            selectedOccurrence = preserved.or(() ->
                    selectLatest && !rows.isEmpty()
                            ? Optional.of(
                                    rows.getLast().occurrenceId()
                            )
                            : Optional.empty());
            if (snapshot.activeEpisodeId().isEmpty()) {
                message = BehaviorGraphOccurrenceInspector
                        .NO_ACTIVE_EPISODE_MESSAGE;
            } else if (rows.isEmpty()) {
                message = BehaviorGraphOccurrenceInspector
                        .NO_OCCURRENCES_MESSAGE;
            } else {
                message = null;
            }
            return selectedOccurrence;
        }

        @Override
        public void showDetails(EventOccurrenceDetailsSnapshot newDetails) {
            recordThread();
            details = Optional.of(newDetails);
        }

        @Override
        public void showNoOccurrenceSelected() {
            recordThread();
            details = Optional.empty();
        }

        @Override
        public void showLoadError() {
            recordThread();
            loadErrorCount++;
        }

        private void recordThread() {
            allCallsOnEdt &= SwingUtilities.isEventDispatchThread();
        }
    }

    private static final class ManualExecutor
            extends AbstractExecutorService {

        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private final AtomicBoolean shutdown = new AtomicBoolean();
        private boolean shutdownNowCalled;

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdownNowCalled = true;
            shutdown.set(true);
            List<Runnable> pending = List.copyOf(tasks);
            tasks.clear();
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
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown.get()) {
                throw new RejectedExecutionException("executor shut down");
            }
            tasks.addLast(Objects.requireNonNull(command, "command"));
        }

        private void runNext() {
            Runnable task = tasks.pollFirst();
            if (task == null) {
                throw new AssertionError("no queued occurrence query");
            }
            task.run();
        }

        private int queuedTaskCount() {
            return tasks.size();
        }
    }
}
