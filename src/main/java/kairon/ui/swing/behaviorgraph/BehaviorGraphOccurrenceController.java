package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.ActiveEpisodeNodeOccurrencesSnapshot;
import kairon.behavior.graph.BehaviorGraphOccurrenceQuery;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.graph.EventOccurrenceDetailsSnapshot;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns structural-node selection and active-episode occurrence inspection.
 */
final class BehaviorGraphOccurrenceController {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            BehaviorGraphOccurrenceController.class
    );

    private final BehaviorGraphOccurrenceQuery query;
    private final View view;
    private final ExecutorService queryExecutor;
    private final AtomicBoolean disposed = new AtomicBoolean();

    private BehaviorGraphVisualizationSnapshot graphSnapshot;
    private NormalizedEventType selectedEventType;
    private EventOccurrenceId selectedOccurrenceId;
    private long listRequestToken;
    private long detailsRequestToken;
    private long appliedEpisodeVersion = -1L;
    private long appliedListGraphVersion = -1L;
    private long selectedTypeCount = -1L;
    private boolean listLoaded;
    private boolean inspectorVisible = true;

    BehaviorGraphOccurrenceController(
            BehaviorGraphOccurrenceQuery query,
            View view
    ) {
        this(query, view, newQueryExecutor());
    }

    BehaviorGraphOccurrenceController(
            BehaviorGraphOccurrenceQuery query,
            View view,
            ExecutorService queryExecutor
    ) {
        requireEdt();
        this.query = Objects.requireNonNull(query, "query");
        this.view = Objects.requireNonNull(view, "view");
        this.queryExecutor = Objects.requireNonNull(
                queryExecutor,
                "queryExecutor"
        );
    }

    void onGraphSnapshotApplied(
            BehaviorGraphVisualizationSnapshot snapshot
    ) {
        requireEdt();
        if (disposed.get()) {
            return;
        }
        Objects.requireNonNull(snapshot, "snapshot");

        BehaviorGraphVisualizationSnapshot previous = graphSnapshot;
        boolean graphChanged = previous == null
                || !previous.graphId().equals(snapshot.graphId());
        boolean episodeChanged = !graphChanged
                && !previous.activeEpisodeId().equals(
                        snapshot.activeEpisodeId()
                );
        graphSnapshot = snapshot;

        if (graphChanged) {
            resetForGraph(snapshot);
            return;
        }
        if (episodeChanged) {
            resetForEpisode(snapshot);
            return;
        }
        if (selectedEventType == null) {
            selectDefaultNode(snapshot);
            return;
        }
        if (!containsNode(snapshot, selectedEventType)) {
            LOGGER.warn(
                    "BEHAVIOR_GRAPH_SELECTED_NODE_UNAVAILABLE graphId={} "
                            + "eventType={}",
                    snapshot.graphId().canonicalValue(),
                    selectedEventType.value()
            );
            clearSelection();
            return;
        }

        long activeCount = activeCount(snapshot, selectedEventType);
        if (!listLoaded || activeCount != selectedTypeCount) {
            selectedTypeCount = activeCount;
            requestOccurrences(false);
        }
    }

    void selectNode(NormalizedEventType eventType) {
        requireEdt();
        if (disposed.get() || graphSnapshot == null) {
            return;
        }
        Objects.requireNonNull(eventType, "eventType");
        if (!containsNode(graphSnapshot, eventType)) {
            LOGGER.warn(
                    "BEHAVIOR_GRAPH_SELECTED_NODE_UNAVAILABLE graphId={} "
                            + "eventType={}",
                    graphSnapshot.graphId().canonicalValue(),
                    eventType.value()
            );
            return;
        }
        if (eventType.equals(selectedEventType)) {
            view.makeNodeVisible(eventType);
            return;
        }

        selectedEventType = eventType;
        selectedOccurrenceId = null;
        selectedTypeCount = activeCount(graphSnapshot, eventType);
        invalidateRequests();
        resetAppliedListIdentity();
        view.setSelectedNode(Optional.of(eventType));
        view.showSelectedNodePending(
                displayName(graphSnapshot, eventType),
                selectedTypeCount,
                graphSnapshot.activeEpisodeId().isPresent()
        );
        view.makeNodeVisible(eventType);
        LOGGER.debug(
                "BEHAVIOR_GRAPH_NODE_SELECTED graphId={} eventType={}",
                graphSnapshot.graphId().canonicalValue(),
                eventType.value()
        );
        requestOccurrences(true);
    }

    void refreshSelectedNode() {
        requireEdt();
        if (disposed.get()
                || graphSnapshot == null
                || selectedEventType == null) {
            return;
        }
        selectedTypeCount = activeCount(
                graphSnapshot,
                selectedEventType
        );
        requestOccurrences(selectedOccurrenceId == null);
    }

    void selectOccurrence(Optional<EventOccurrenceId> occurrenceId) {
        requireEdt();
        if (disposed.get() || !inspectorVisible) {
            return;
        }
        Objects.requireNonNull(occurrenceId, "occurrenceId");
        selectedOccurrenceId = occurrenceId.orElse(null);
        detailsRequestToken++;
        if (selectedOccurrenceId == null) {
            view.showNoOccurrenceSelected();
            return;
        }
        view.showNoOccurrenceSelected();
        requestDetails(selectedOccurrenceId);
    }

    void setInspectorVisible(boolean visible) {
        requireEdt();
        if (disposed.get() || inspectorVisible == visible) {
            return;
        }
        inspectorVisible = visible;
        invalidateRequests();
        resetAppliedListIdentity();
        if (visible
                && graphSnapshot != null
                && selectedEventType != null) {
            selectedTypeCount = activeCount(
                    graphSnapshot,
                    selectedEventType
            );
            requestOccurrences(selectedOccurrenceId == null);
        }
    }

    void onGraphUnavailable() {
        requireEdt();
        if (disposed.get()) {
            return;
        }
        graphSnapshot = null;
        clearSelection();
    }

    void dispose() {
        requireEdt();
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        invalidateRequests();
        queryExecutor.shutdownNow();
    }

    Optional<NormalizedEventType> selectedEventType() {
        requireEdt();
        return Optional.ofNullable(selectedEventType);
    }

    Optional<EventOccurrenceId> selectedOccurrenceId() {
        requireEdt();
        return Optional.ofNullable(selectedOccurrenceId);
    }

    boolean isInspectorVisible() {
        requireEdt();
        return inspectorVisible;
    }

    private void resetForGraph(
            BehaviorGraphVisualizationSnapshot snapshot
    ) {
        selectedEventType = null;
        selectedOccurrenceId = null;
        selectedTypeCount = -1L;
        invalidateRequests();
        resetAppliedListIdentity();
        view.setSelectedNode(Optional.empty());
        view.showInitial();
        selectDefaultNode(snapshot);
    }

    private void resetForEpisode(
            BehaviorGraphVisualizationSnapshot snapshot
    ) {
        selectedOccurrenceId = null;
        invalidateRequests();
        resetAppliedListIdentity();

        if (selectedEventType == null
                || !containsNode(snapshot, selectedEventType)) {
            selectedEventType = null;
            view.setSelectedNode(Optional.empty());
            view.showInitial();
            selectDefaultNode(snapshot);
            return;
        }

        selectedTypeCount = activeCount(snapshot, selectedEventType);
        view.showSelectedNodePending(
                displayName(snapshot, selectedEventType),
                selectedTypeCount,
                snapshot.activeEpisodeId().isPresent()
        );
        LOGGER.debug(
                "BEHAVIOR_GRAPH_ACTIVE_EPISODE_CHANGED graphId={} "
                        + "episodeId={}",
                snapshot.graphId().canonicalValue(),
                snapshot.activeEpisodeId()
                        .map(SystemEpisodeId::value)
                        .orElse("none")
        );
        requestOccurrences(false);
    }

    private void selectDefaultNode(
            BehaviorGraphVisualizationSnapshot snapshot
    ) {
        Optional<NormalizedEventType> defaultNode =
                snapshot.currentEventType()
                        .filter(type -> containsNode(snapshot, type))
                        .or(() -> containsNode(
                                snapshot,
                                NormalizedEventType.SYSTEM_ENTRY
                        )
                                ? Optional.of(
                                        NormalizedEventType.SYSTEM_ENTRY
                                )
                                : Optional.empty());
        defaultNode.ifPresent(this::selectNode);
    }

    private void clearSelection() {
        selectedEventType = null;
        selectedOccurrenceId = null;
        selectedTypeCount = -1L;
        invalidateRequests();
        resetAppliedListIdentity();
        view.setSelectedNode(Optional.empty());
        view.showInitial();
    }

    private void requestOccurrences(boolean selectLatest) {
        requireEdt();
        if (disposed.get()
                || !inspectorVisible
                || graphSnapshot == null
                || selectedEventType == null) {
            return;
        }

        long token = ++listRequestToken;
        GraphId requestedGraphId = graphSnapshot.graphId();
        Optional<SystemEpisodeId> requestedEpisodeId =
                graphSnapshot.activeEpisodeId();
        NormalizedEventType requestedType = selectedEventType;
        long requestedGraphVersion = graphSnapshot.graphVersion();
        LOGGER.debug(
                "BEHAVIOR_GRAPH_OCCURRENCES_REQUESTED graphId={} "
                        + "eventType={} requestToken={}",
                requestedGraphId.canonicalValue(),
                requestedType.value(),
                token
        );
        try {
            queryExecutor.execute(() -> {
                try {
                    ActiveEpisodeNodeOccurrencesSnapshot loaded =
                            query.getActiveEpisodeOccurrences(
                                    requestedGraphId,
                                    requestedType
                            );
                    SwingUtilities.invokeLater(() ->
                            applyOccurrences(
                                    token,
                                    requestedGraphId,
                                    requestedEpisodeId,
                                    requestedType,
                                    requestedGraphVersion,
                                    selectLatest,
                                    loaded
                            ));
                } catch (RuntimeException failure) {
                    SwingUtilities.invokeLater(() ->
                            applyOccurrenceFailure(
                                    token,
                                    requestedGraphId,
                                    requestedEpisodeId,
                                    requestedType,
                                    failure
                            ));
                }
            });
        } catch (RejectedExecutionException failure) {
            applyOccurrenceFailure(
                    token,
                    requestedGraphId,
                    requestedEpisodeId,
                    requestedType,
                    failure
            );
        }
    }

    private void applyOccurrences(
            long token,
            GraphId requestedGraphId,
            Optional<SystemEpisodeId> requestedEpisodeId,
            NormalizedEventType requestedType,
            long requestedGraphVersion,
            boolean selectLatest,
            ActiveEpisodeNodeOccurrencesSnapshot loaded
    ) {
        requireEdt();
        if (isStaleListRequest(
                token,
                requestedGraphId,
                requestedEpisodeId,
                requestedType
        )) {
            logStale("occurrence list", token);
            return;
        }
        if (!loaded.graphId().equals(requestedGraphId)
                || !loaded.activeEpisodeId().equals(requestedEpisodeId)
                || !loaded.eventType().equals(requestedType)) {
            LOGGER.debug(
                    "BEHAVIOR_GRAPH_OCCURRENCES_IDENTITY_MISMATCH "
                            + "requestToken={}",
                    token
            );
            return;
        }
        if (loaded.graphVersion() < graphSnapshot.graphVersion()) {
            logStale("occurrence version", token);
            if (requestedGraphVersion < graphSnapshot.graphVersion()) {
                requestOccurrences(false);
            } else {
                view.showLoadError();
                LOGGER.warn(
                        "BEHAVIOR_GRAPH_OCCURRENCES_VERSION_MISMATCH "
                                + "graphId={} requestedVersion={} "
                                + "loadedVersion={}",
                        requestedGraphId.canonicalValue(),
                        requestedGraphVersion,
                        loaded.graphVersion()
                );
            }
            return;
        }
        if (loaded.episodeVersion() < appliedEpisodeVersion
                || loaded.graphVersion() < appliedListGraphVersion) {
            logStale("occurrence version", token);
            return;
        }

        long expectedCount = activeCount(
                graphSnapshot,
                requestedType
        );
        if (loaded.graphVersion() == graphSnapshot.graphVersion()
                && loaded.occurrences().size() != expectedCount) {
            LOGGER.warn(
                    "BEHAVIOR_GRAPH_OCCURRENCE_COUNT_MISMATCH graphId={} "
                            + "eventType={} graphCount={} queryRows={}",
                    requestedGraphId.canonicalValue(),
                    requestedType.value(),
                    expectedCount,
                    loaded.occurrences().size()
            );
        }

        EventOccurrenceId previousSelection = selectedOccurrenceId;
        Optional<EventOccurrenceId> appliedSelection =
                view.applyOccurrences(
                        loaded,
                        Optional.ofNullable(previousSelection),
                        selectLatest
                );
        selectedOccurrenceId = appliedSelection.orElse(null);
        selectedTypeCount = loaded.occurrences().size();
        appliedEpisodeVersion = loaded.episodeVersion();
        appliedListGraphVersion = loaded.graphVersion();
        listLoaded = true;

        if (selectedOccurrenceId == null) {
            view.showNoOccurrenceSelected();
        } else if (!selectedOccurrenceId.equals(previousSelection)) {
            requestDetails(selectedOccurrenceId);
        } else {
            LOGGER.debug(
                    "BEHAVIOR_GRAPH_SELECTED_OCCURRENCE_PRESERVED "
                            + "occurrenceId={}",
                    selectedOccurrenceId.value()
            );
        }
        LOGGER.debug(
                "BEHAVIOR_GRAPH_OCCURRENCE_INSPECTOR_REFRESHED "
                        + "graphId={} eventType={} rows={}",
                requestedGraphId.canonicalValue(),
                requestedType.value(),
                loaded.occurrences().size()
        );
    }

    private void applyOccurrenceFailure(
            long token,
            GraphId requestedGraphId,
            Optional<SystemEpisodeId> requestedEpisodeId,
            NormalizedEventType requestedType,
            RuntimeException failure
    ) {
        requireEdt();
        if (isStaleListRequest(
                token,
                requestedGraphId,
                requestedEpisodeId,
                requestedType
        )) {
            return;
        }
        view.showLoadError();
        LOGGER.warn(
                "BEHAVIOR_GRAPH_OCCURRENCES_FAILED graphId={} "
                        + "eventType={} category={}",
                requestedGraphId.canonicalValue(),
                requestedType.value(),
                failure.getClass().getSimpleName(),
                failure
        );
    }

    private void requestDetails(EventOccurrenceId occurrenceId) {
        requireEdt();
        if (disposed.get()
                || !inspectorVisible
                || graphSnapshot == null
                || selectedEventType == null
                || graphSnapshot.activeEpisodeId().isEmpty()) {
            view.showNoOccurrenceSelected();
            return;
        }

        long token = ++detailsRequestToken;
        GraphId requestedGraphId = graphSnapshot.graphId();
        SystemEpisodeId requestedEpisodeId =
                graphSnapshot.activeEpisodeId().orElseThrow();
        NormalizedEventType requestedType = selectedEventType;
        LOGGER.debug(
                "BEHAVIOR_GRAPH_OCCURRENCE_DETAILS_REQUESTED graphId={} "
                        + "occurrenceId={} requestToken={}",
                requestedGraphId.canonicalValue(),
                occurrenceId.value(),
                token
        );
        try {
            queryExecutor.execute(() -> {
                try {
                    Optional<EventOccurrenceDetailsSnapshot> loaded =
                            query.getActiveEpisodeOccurrenceDetails(
                                    requestedGraphId,
                                    requestedEpisodeId,
                                    occurrenceId
                            );
                    SwingUtilities.invokeLater(() -> applyDetails(
                            token,
                            requestedGraphId,
                            requestedEpisodeId,
                            requestedType,
                            occurrenceId,
                            loaded
                    ));
                } catch (RuntimeException failure) {
                    SwingUtilities.invokeLater(() ->
                            applyDetailsFailure(
                                    token,
                                    requestedGraphId,
                                    requestedEpisodeId,
                                    requestedType,
                                    occurrenceId,
                                    failure
                            ));
                }
            });
        } catch (RejectedExecutionException failure) {
            applyDetailsFailure(
                    token,
                    requestedGraphId,
                    requestedEpisodeId,
                    requestedType,
                    occurrenceId,
                    failure
            );
        }
    }

    private void applyDetails(
            long token,
            GraphId requestedGraphId,
            SystemEpisodeId requestedEpisodeId,
            NormalizedEventType requestedType,
            EventOccurrenceId requestedOccurrenceId,
            Optional<EventOccurrenceDetailsSnapshot> loaded
    ) {
        requireEdt();
        if (isStaleDetailsRequest(
                token,
                requestedGraphId,
                requestedEpisodeId,
                requestedType,
                requestedOccurrenceId
        )) {
            logStale("occurrence details", token);
            return;
        }
        if (loaded.isEmpty()) {
            LOGGER.warn(
                    "BEHAVIOR_GRAPH_OCCURRENCE_DETAILS_MISSING graphId={} "
                            + "occurrenceId={}",
                    requestedGraphId.canonicalValue(),
                    requestedOccurrenceId.value()
            );
            view.showLoadError();
            return;
        }

        EventOccurrenceDetailsSnapshot details = loaded.orElseThrow();
        if (!details.graphId().equals(requestedGraphId)
                || !details.episodeId().equals(requestedEpisodeId)
                || !details.occurrenceId().equals(requestedOccurrenceId)
                || !details.eventType().equals(requestedType)) {
            LOGGER.warn(
                    "BEHAVIOR_GRAPH_OCCURRENCE_DETAILS_IDENTITY_MISMATCH "
                            + "graphId={} occurrenceId={}",
                    requestedGraphId.canonicalValue(),
                    requestedOccurrenceId.value()
            );
            return;
        }
        view.showDetails(details);
    }

    private void applyDetailsFailure(
            long token,
            GraphId requestedGraphId,
            SystemEpisodeId requestedEpisodeId,
            NormalizedEventType requestedType,
            EventOccurrenceId requestedOccurrenceId,
            RuntimeException failure
    ) {
        requireEdt();
        if (isStaleDetailsRequest(
                token,
                requestedGraphId,
                requestedEpisodeId,
                requestedType,
                requestedOccurrenceId
        )) {
            return;
        }
        view.showLoadError();
        LOGGER.warn(
                "BEHAVIOR_GRAPH_OCCURRENCE_DETAILS_FAILED graphId={} "
                        + "occurrenceId={} category={}",
                requestedGraphId.canonicalValue(),
                requestedOccurrenceId.value(),
                failure.getClass().getSimpleName(),
                failure
        );
    }

    private boolean isStaleListRequest(
            long token,
            GraphId graphId,
            Optional<SystemEpisodeId> episodeId,
            NormalizedEventType eventType
    ) {
        return disposed.get()
                || !inspectorVisible
                || token != listRequestToken
                || graphSnapshot == null
                || !graphSnapshot.graphId().equals(graphId)
                || !graphSnapshot.activeEpisodeId().equals(episodeId)
                || !eventType.equals(selectedEventType);
    }

    private boolean isStaleDetailsRequest(
            long token,
            GraphId graphId,
            SystemEpisodeId episodeId,
            NormalizedEventType eventType,
            EventOccurrenceId occurrenceId
    ) {
        return disposed.get()
                || !inspectorVisible
                || token != detailsRequestToken
                || graphSnapshot == null
                || !graphSnapshot.graphId().equals(graphId)
                || !graphSnapshot.activeEpisodeId()
                .equals(Optional.of(episodeId))
                || !eventType.equals(selectedEventType)
                || !occurrenceId.equals(selectedOccurrenceId);
    }

    private void invalidateRequests() {
        listRequestToken++;
        detailsRequestToken++;
    }

    private void resetAppliedListIdentity() {
        appliedEpisodeVersion = -1L;
        appliedListGraphVersion = -1L;
        listLoaded = false;
    }

    private static boolean containsNode(
            BehaviorGraphVisualizationSnapshot snapshot,
            NormalizedEventType eventType
    ) {
        return snapshot.nodes().stream()
                .anyMatch(node -> node.eventType().equals(eventType));
    }

    private static long activeCount(
            BehaviorGraphVisualizationSnapshot snapshot,
            NormalizedEventType eventType
    ) {
        return snapshot.nodes().stream()
                .filter(node -> node.eventType().equals(eventType))
                .findFirst()
                .map(
                        BehaviorGraphVisualizationSnapshot.VisualizationNode
                                ::activeEpisodeOccurrenceCount
                )
                .orElse(0L);
    }

    private static String displayName(
            BehaviorGraphVisualizationSnapshot snapshot,
            NormalizedEventType eventType
    ) {
        return snapshot.nodes().stream()
                .filter(node -> node.eventType().equals(eventType))
                .findFirst()
                .map(
                        BehaviorGraphVisualizationSnapshot.VisualizationNode
                                ::displayName
                )
                .orElse(eventType.value());
    }

    private static void logStale(String resultKind, long token) {
        LOGGER.debug(
                "BEHAVIOR_GRAPH_OCCURRENCE_STALE_RESULT_DISCARDED "
                        + "kind={} requestToken={}",
                resultKind,
                token
        );
    }

    private static ExecutorService newQueryExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(
                    task,
                    "behavior-graph-occurrence-query"
            );
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "Occurrence controller changes must run on the Swing EDT"
            );
        }
    }

    interface View {

        void setSelectedNode(Optional<NormalizedEventType> eventType);

        void makeNodeVisible(NormalizedEventType eventType);

        void showInitial();

        void showSelectedNodePending(
                String displayName,
                long activeOccurrenceCount,
                boolean activeEpisodePresent
        );

        Optional<EventOccurrenceId> applyOccurrences(
                ActiveEpisodeNodeOccurrencesSnapshot snapshot,
                Optional<EventOccurrenceId> occurrenceToPreserve,
                boolean selectLatest
        );

        void showDetails(EventOccurrenceDetailsSnapshot details);

        void showNoOccurrenceSelected();

        void showLoadError();
    }
}
