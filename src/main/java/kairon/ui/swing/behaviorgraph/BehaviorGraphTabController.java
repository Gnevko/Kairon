package kairon.ui.swing.behaviorgraph;

import kairon.behavior.event.BehaviorGraphEvent;
import kairon.behavior.event.BehaviorGraphEvent.ActiveGraphChanged;
import kairon.behavior.event.BehaviorGraphEvent.ReplayCompleted;
import kairon.behavior.event.BehaviorGraphEventSource;
import kairon.behavior.graph.BehaviorGraphVisualizationQuery;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.model.GraphId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Connects internal graph notifications and read-only snapshots to the tab.
 */
final class BehaviorGraphTabController {

    static final int DEFAULT_DEBOUNCE_MILLIS = 150;

    private static final Logger LOGGER =
            LoggerFactory.getLogger(BehaviorGraphTabController.class);

    private final BehaviorGraphVisualizationQuery query;
    private final View view;
    private final ExecutorService snapshotLoader;
    private final Clock clock;
    private final Timer refreshTimer;
    private final BehaviorGraphEventSource.Subscription subscription;
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final AtomicReference<BehaviorGraphEvent> pendingEvent =
            new AtomicReference<>();
    private final AtomicBoolean eventHandoffScheduled =
            new AtomicBoolean();
    private final AtomicBoolean pendingActiveGraphChange =
            new AtomicBoolean();
    private final AtomicBoolean pendingReplayCompletion =
            new AtomicBoolean();

    private boolean selected;
    private boolean auxiliaryViewActive;
    private boolean dirty = true;
    private boolean centerOnSuccessfulRefresh;
    private boolean loadedOnce;
    private boolean recoveringFromError;
    private long requestSequence;
    private long latestRequestedSequence;
    private GraphId appliedGraphId;
    private long appliedGraphVersion = -1L;
    private long appliedTopologyVersion = -1L;

    BehaviorGraphTabController(
            BehaviorGraphVisualizationQuery query,
            BehaviorGraphEventSource eventSource,
            View view
    ) {
        this(
                query,
                eventSource,
                view,
                newSnapshotLoader(),
                Clock.systemUTC(),
                DEFAULT_DEBOUNCE_MILLIS
        );
    }

    BehaviorGraphTabController(
            BehaviorGraphVisualizationQuery query,
            BehaviorGraphEventSource eventSource,
            View view,
            ExecutorService snapshotLoader,
            Clock clock,
            int debounceMillis
    ) {
        requireEdt();
        this.query = Objects.requireNonNull(query, "query");
        this.view = Objects.requireNonNull(view, "view");
        this.snapshotLoader = Objects.requireNonNull(
                snapshotLoader,
                "snapshotLoader"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
        if (debounceMillis < 1) {
            throw new IllegalArgumentException(
                    "debounceMillis must be positive"
            );
        }
        refreshTimer = new Timer(
                debounceMillis,
                event -> requestLatestSnapshot()
        );
        refreshTimer.setRepeats(false);
        subscription = Objects.requireNonNull(eventSource, "eventSource")
                .subscribe(this::onBehaviorGraphEvent);
    }

    void setSelected(boolean selected) {
        requireEdt();
        if (disposed.get() || this.selected == selected) {
            return;
        }
        this.selected = selected;
        if (!refreshActive()) {
            refreshTimer.stop();
            dirty = true;
            return;
        }
        if (!selected) {
            return;
        }
        centerOnSuccessfulRefresh = true;
        dirty = true;
        refreshTimer.stop();
        requestLatestSnapshot();
    }

    void setAuxiliaryViewActive(boolean active) {
        requireEdt();
        if (disposed.get() || auxiliaryViewActive == active) {
            return;
        }
        boolean wasRefreshActive = refreshActive();
        auxiliaryViewActive = active;
        if (!refreshActive()) {
            refreshTimer.stop();
            dirty = true;
            return;
        }
        if (!wasRefreshActive) {
            dirty = true;
            refreshTimer.stop();
            requestLatestSnapshot();
        }
    }

    void dispose() {
        requireEdt();
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        refreshTimer.stop();
        requestSequence++;
        latestRequestedSequence = requestSequence;
        subscription.close();
        snapshotLoader.shutdownNow();
    }

    boolean isDirty() {
        requireEdt();
        return dirty;
    }

    boolean isRefreshPending() {
        requireEdt();
        return refreshTimer.isRunning();
    }

    private void onBehaviorGraphEvent(BehaviorGraphEvent event) {
        Objects.requireNonNull(event, "event");
        if (disposed.get()) {
            return;
        }
        LOGGER.debug(
                "BEHAVIOR_GRAPH_UI_EVENT_RECEIVED eventType={} graphId={}",
                event.getClass().getSimpleName(),
                event.graphId().canonicalValue()
        );
        pendingEvent.set(event);
        if (event instanceof ActiveGraphChanged) {
            pendingActiveGraphChange.set(true);
        }
        if (event instanceof ReplayCompleted) {
            pendingReplayCompletion.set(true);
        }
        scheduleEventHandoff();
    }

    private void scheduleEventHandoff() {
        if (eventHandoffScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(this::drainEventsOnEdt);
        }
    }

    private void drainEventsOnEdt() {
        requireEdt();
        BehaviorGraphEvent event = pendingEvent.getAndSet(null);
        boolean activeGraphChanged =
                pendingActiveGraphChange.getAndSet(false);
        boolean replayCompleted =
                pendingReplayCompletion.getAndSet(false);
        eventHandoffScheduled.set(false);
        if (event != null) {
            handleEventOnEdt(
                    event,
                    activeGraphChanged,
                    replayCompleted
            );
        }
        if (pendingEvent.get() != null && !disposed.get()) {
            scheduleEventHandoff();
        }
    }

    private void handleEventOnEdt(
            BehaviorGraphEvent event,
            boolean activeGraphChanged,
            boolean replayCompleted
    ) {
        requireEdt();
        if (disposed.get()) {
            return;
        }
        dirty = true;
        if (activeGraphChanged) {
            centerOnSuccessfulRefresh = true;
            LOGGER.info(
                    "BEHAVIOR_GRAPH_UI_ACTIVE_GRAPH_CHANGED graphId={}",
                    event.graphId().canonicalValue()
            );
        }
        if (!refreshActive()) {
            return;
        }
        if (replayCompleted) {
            refreshTimer.stop();
            requestLatestSnapshot();
            return;
        }
        if (refreshTimer.isRunning()) {
            LOGGER.debug("BEHAVIOR_GRAPH_UI_REFRESH_COALESCED");
        }
        refreshTimer.restart();
    }

    private void requestLatestSnapshot() {
        requireEdt();
        if (disposed.get() || !refreshActive()) {
            dirty = true;
            return;
        }
        long sequence = ++requestSequence;
        latestRequestedSequence = sequence;
        Instant evaluationTime = clock.instant();
        LOGGER.debug(
                "BEHAVIOR_GRAPH_UI_SNAPSHOT_REQUESTED requestSequence={}",
                sequence
        );
        try {
            snapshotLoader.execute(() -> loadSnapshot(
                    sequence,
                    evaluationTime
            ));
        } catch (RejectedExecutionException rejected) {
            if (!disposed.get()) {
                applyFailure(sequence, rejected);
            }
        }
    }

    private void loadSnapshot(long sequence, Instant evaluationTime) {
        try {
            Optional<GraphId> activeGraphId = query.getActiveGraphId();
            Optional<BehaviorGraphVisualizationSnapshot> snapshot =
                    activeGraphId.flatMap(graphId ->
                            query.getVisualizationSnapshot(
                                    graphId,
                                    evaluationTime
                            ));
            SwingUtilities.invokeLater(() -> applyLoadedSnapshot(
                    sequence,
                    activeGraphId,
                    snapshot
            ));
        } catch (RuntimeException failure) {
            SwingUtilities.invokeLater(() ->
                    applyFailure(sequence, failure));
        }
    }

    private void applyLoadedSnapshot(
            long sequence,
            Optional<GraphId> activeGraphId,
            Optional<BehaviorGraphVisualizationSnapshot> loaded
    ) {
        requireEdt();
        if (isStaleRequest(sequence) || !refreshActive()) {
            dirty = true;
            LOGGER.debug(
                    "BEHAVIOR_GRAPH_UI_STALE_SNAPSHOT_DISCARDED "
                            + "requestSequence={}",
                    sequence
            );
            return;
        }
        if (activeGraphId.isEmpty()) {
            LOGGER.warn("BEHAVIOR_GRAPH_UI_ACTIVE_GRAPH_UNAVAILABLE");
            clearAppliedIdentity();
            view.showNoActiveShip();
            dirty = false;
            recoveringFromError = false;
            return;
        }
        if (loaded.isEmpty()) {
            clearAppliedIdentity();
            appliedGraphId = activeGraphId.orElseThrow();
            view.showEmptyGraph();
            dirty = false;
            recoveringFromError = false;
            return;
        }

        BehaviorGraphVisualizationSnapshot snapshot = loaded.orElseThrow();
        if (!snapshot.graphId().equals(activeGraphId.orElseThrow())) {
            LOGGER.warn(
                    "BEHAVIOR_GRAPH_UI_SNAPSHOT_ID_MISMATCH activeGraphId={} "
                            + "snapshotGraphId={}",
                    activeGraphId.orElseThrow().canonicalValue(),
                    snapshot.graphId().canonicalValue()
            );
            dirty = true;
            return;
        }
        if (snapshot.graphId().equals(appliedGraphId)
                && snapshot.graphVersion() < appliedGraphVersion) {
            LOGGER.debug(
                    "BEHAVIOR_GRAPH_UI_STALE_VERSION_DISCARDED graphId={} "
                            + "loadedVersion={} appliedVersion={}",
                    snapshot.graphId().canonicalValue(),
                    snapshot.graphVersion(),
                    appliedGraphVersion
            );
            dirty = true;
            return;
        }

        boolean graphReplaced = !snapshot.graphId().equals(appliedGraphId);
        boolean fullRelayout = graphReplaced
                || snapshot.topologyVersion() != appliedTopologyVersion;
        boolean graphWasUnavailable = !view.hasGraphData();
        boolean relayoutPerformed = view.applySnapshot(
                snapshot,
                fullRelayout
        );
        appliedGraphId = snapshot.graphId();
        appliedGraphVersion = snapshot.graphVersion();
        appliedTopologyVersion = snapshot.topologyVersion();
        dirty = false;

        if (relayoutPerformed) {
            LOGGER.debug(
                    "BEHAVIOR_GRAPH_UI_FULL_RELAYOUT graphId={} "
                            + "topologyVersion={}",
                    snapshot.graphId().canonicalValue(),
                    snapshot.topologyVersion()
            );
        } else {
            LOGGER.debug(
                    "BEHAVIOR_GRAPH_UI_WEIGHT_ONLY_REPAINT graphId={} "
                            + "graphVersion={}",
                    snapshot.graphId().canonicalValue(),
                    snapshot.graphVersion()
            );
        }
        if (!loadedOnce) {
            loadedOnce = true;
            LOGGER.info(
                    "BEHAVIOR_GRAPH_UI_FIRST_SNAPSHOT_LOADED graphId={}",
                    snapshot.graphId().canonicalValue()
            );
        }
        if (recoveringFromError) {
            recoveringFromError = false;
            LOGGER.info(
                    "BEHAVIOR_GRAPH_UI_REFRESH_RESTORED graphId={}",
                    snapshot.graphId().canonicalValue()
            );
        }

        boolean becameAvailable = graphWasUnavailable
                && !snapshot.nodes().isEmpty();
        if (centerOnSuccessfulRefresh
                || graphReplaced
                || becameAvailable) {
            if (selected) {
                centerOnSuccessfulRefresh = false;
                view.centerCurrentNodeLater();
            } else {
                centerOnSuccessfulRefresh = true;
            }
        }
    }

    private void applyFailure(long sequence, RuntimeException failure) {
        requireEdt();
        if (isStaleRequest(sequence)
                || disposed.get()
                || !refreshActive()) {
            return;
        }
        dirty = true;
        recoveringFromError = true;
        view.showRefreshError();
        LOGGER.error(
                "BEHAVIOR_GRAPH_UI_SNAPSHOT_FAILED category={}",
                failure.getClass().getSimpleName(),
                failure
        );
    }

    private boolean isStaleRequest(long sequence) {
        return disposed.get() || sequence != latestRequestedSequence;
    }

    private boolean refreshActive() {
        return selected || auxiliaryViewActive;
    }

    private void clearAppliedIdentity() {
        appliedGraphId = null;
        appliedGraphVersion = -1L;
        appliedTopologyVersion = -1L;
    }

    private static ExecutorService newSnapshotLoader() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(
                    task,
                    "behavior-graph-visualization"
            );
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "Behavior graph controller changes must run on the EDT"
            );
        }
    }

    interface View {

        boolean applySnapshot(
                BehaviorGraphVisualizationSnapshot snapshot,
                boolean fullRelayout
        );

        void showNoActiveShip();

        void showEmptyGraph();

        void showRefreshError();

        boolean hasGraphData();

        void centerCurrentNodeLater();
    }
}
