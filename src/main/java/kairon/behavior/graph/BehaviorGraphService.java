package kairon.behavior.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import kairon.behavior.classify.BehaviorOccurrenceProjectionPolicy;
import kairon.behavior.classify.BodySurveySelectionPolicy;
import kairon.behavior.classify.EventSignificancePolicy;
import kairon.behavior.classify.EventSignificancePolicy.EventSignificance;
import kairon.behavior.classify.RouteTargetSelectionPolicy;
import kairon.behavior.context.BehaviorContextAdapter;
import kairon.behavior.context.TransitionContextKeyFactory;
import kairon.behavior.event.BehaviorGraphEvent;
import kairon.behavior.event.BehaviorGraphEvent.ActiveGraphChanged;
import kairon.behavior.event.BehaviorGraphEvent.BehaviorGraphCreated;
import kairon.behavior.event.BehaviorGraphEvent.BehaviorGraphUpdated;
import kairon.behavior.event.BehaviorGraphEvent.EventOccurrenceRecorded;
import kairon.behavior.event.BehaviorGraphEvent.EventTypeNodeCreated;
import kairon.behavior.event.BehaviorGraphEvent.GraphCursorChanged;
import kairon.behavior.event.BehaviorGraphEvent.NextEventPredictionChanged;
import kairon.behavior.event.BehaviorGraphEvent.OccurrenceTransitionRecorded;
import kairon.behavior.event.BehaviorGraphEvent.ReplayCompleted;
import kairon.behavior.event.BehaviorGraphEvent.SystemEpisodeCompleted;
import kairon.behavior.event.BehaviorGraphEvent.SystemEpisodeStarted;
import kairon.behavior.event.BehaviorGraphEvent.TransitionEdgeCreated;
import kairon.behavior.event.BehaviorGraphEventBus;
import kairon.behavior.event.BehaviorGraphEventSource;
import kairon.behavior.event.BehaviorGraphListener;
import kairon.behavior.model.ContextKey;
import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.EdgeKey;
import kairon.behavior.model.EpisodeCompletionReason;
import kairon.behavior.model.EpisodeEntrySource;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.EventOccurrenceSource;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.NextEventPrediction;
import kairon.behavior.model.OccurrenceTransition;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.ShipBehaviorGraphSummary;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.model.SystemEpisodeSummary;
import kairon.behavior.model.TransitionEdgeView;
import kairon.behavior.normalize.BehaviorEventNormalizer;
import kairon.behavior.normalize.NormalizedBehaviorEvent;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.behavior.persistence.BehaviorGraphStore;
import kairon.behavior.status.StatusStateDeltaAdapter.StatusDeltaBatch;
import kairon.behavior.status.StatusStateDeltaAdapter.StatusStateDelta;
import kairon.behavior.snapshot.ActiveEpisodeSituation;
import kairon.behavior.snapshot.BehaviorSituationCaptureStatus;
import kairon.behavior.snapshot.BehaviorSituationInconsistencyException;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.behavior.snapshot.SituationNextEventPrediction;
import kairon.behavior.snapshot.SituationOccurrence;
import kairon.config.KaironConfiguration.BehaviorGraphConfiguration;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalObservationAdapter.JournalSourcePosition;
import kairon.observation.journal.event.session.Shutdown;
import kairon.observation.journal.event.ship.Loadout;
import kairon.observation.journal.event.travel.FSDJump;
import kairon.observation.journal.event.travel.Location;
import kairon.observation.status.StatusObservationAdapter.StatusSourcePosition;
import kairon.observation.status.StatusSnapshotObservation;
import kairon.semantics.BodyIdentity;
import kairon.semantics.SystemVisitPolicy;
import kairon.semantics.SystemVisitPolicy.SystemVisitState;
import kairon.semantics.SystemVisitTransition;
import kairon.state.CurrentGameStateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

/**
 * Single-writer deterministic projection from journal observations to exact
 * per-system paths and per-ship aggregate behavior graphs.
 */
public final class BehaviorGraphService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(BehaviorGraphService.class);
    private static final BehaviorGraphDisplayNameResolver
            DISPLAY_NAME_RESOLVER =
            new BehaviorGraphDisplayNameResolver();

    private final BehaviorGraphStore store;
    private final BehaviorGraphRegistry registry;
    private final BehaviorContextAdapter contextAdapter;
    private final EventSignificancePolicy significancePolicy;
    private final BehaviorEventNormalizer normalizer;
    private final BehaviorOccurrenceProjectionPolicy occurrenceProjectionPolicy;
    private final RouteTargetSelectionPolicy routeTargetPolicy;
    private final BodySurveySelectionPolicy bodySurveyPolicy;
    private final TransitionContextKeyFactory contextKeyFactory;
    private final TransitionProbabilityCalculator probabilityCalculator;
    private final Duration halfLife;
    private final int snapshotEverySignificantEvents;
    private final BehaviorGraphEventBus eventBus;
    private final Set<EventOccurrenceId> occurrencesSeenInProcess =
            new HashSet<>();
    private final Map<GraphId, GraphRevision> graphRevisions =
            new TreeMap<>();

    private GraphId activeGraphId;
    private SystemEpisode activeEpisode;
    private ReplayEpisodeProjection replayProjection;
    private long lastBusSequence;
    private Instant latestEventTime = Instant.EPOCH;
    private int significantEventsSinceSnapshot;
    private PendingLocation pendingLocation;
    private ContextSnapshot currentContext;
    private boolean closed;

    public BehaviorGraphService(
            BehaviorGraphConfiguration configuration,
            BehaviorGraphStore store
    ) {
        this(
                configuration,
                store,
                new EventSignificancePolicy(),
                new BehaviorEventNormalizer(),
                new TransitionContextKeyFactory(),
                BehaviorGraphListener.NOOP
        );
    }

    public BehaviorGraphService(
            BehaviorGraphConfiguration configuration,
            BehaviorGraphStore store,
            EventSignificancePolicy significancePolicy,
            BehaviorEventNormalizer normalizer,
            TransitionContextKeyFactory contextKeyFactory,
            BehaviorGraphListener listener
    ) {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.enabled()) {
            throw new IllegalArgumentException(
                    "BehaviorGraphService requires enabled configuration"
            );
        }
        this.store = Objects.requireNonNull(store, "store");
        this.registry = new BehaviorGraphRegistry(store);
        this.contextAdapter = new BehaviorContextAdapter();
        this.currentContext = contextAdapter.toContextSnapshot(
                CurrentGameStateSnapshot.unknown()
        );
        this.significancePolicy = Objects.requireNonNull(
                significancePolicy,
                "significancePolicy"
        );
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.occurrenceProjectionPolicy =
                new BehaviorOccurrenceProjectionPolicy();
        this.routeTargetPolicy = new RouteTargetSelectionPolicy();
        this.bodySurveyPolicy = new BodySurveySelectionPolicy();
        this.contextKeyFactory = Objects.requireNonNull(
                contextKeyFactory,
                "contextKeyFactory"
        );
        this.halfLife = configuration.weightHalfLife();
        this.snapshotEverySignificantEvents =
                configuration.snapshotEverySignificantEvents();
        BehaviorGraphListener initialListener = Objects.requireNonNull(
                listener,
                "listener"
        );
        this.eventBus = new BehaviorGraphEventBus();
        if (initialListener != BehaviorGraphListener.NOOP) {
            eventBus.subscribe(initialListener);
        }
        this.probabilityCalculator = new TransitionProbabilityCalculator(
                halfLife,
                configuration.contextPriorStrength(),
                contextKeyFactory
        );
    }

    public synchronized BehaviorGraphApplyResult onObservation(
            PublishedObservation<? extends JournalEventObservation> observation,
            CurrentGameStateSnapshot currentState,
            CurrentGameStateSnapshot observationContext
    ) {
        requireOpen();
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(currentState, "currentState");
        Objects.requireNonNull(observationContext, "observationContext");
        requireIncreasingBusSequence(observation);
        GraphCommitMarker before = graphCommitMarker();

        SourceReference source = journalSourceReference(observation);
        Instant eventTime = deterministicEventTime(observation);
        if (eventTime.isAfter(latestEventTime)) {
            latestEventTime = eventTime;
        }

        currentContext = contextAdapter.toContextSnapshot(currentState);
        ContextSnapshot occurrenceContext =
                contextAdapter.toContextSnapshot(observationContext);
        JournalEventObservation payload = observation.payload();
        Optional<GraphId> resolvedGraphId =
                contextAdapter.graphId(currentState);

        if (resolvedGraphId.isPresent()
                && activeGraphId != null
                && !activeGraphId.equals(resolvedGraphId.orElseThrow())) {
            switchShip(
                    observation,
                    source,
                    eventTime,
                    currentContext,
                    resolvedGraphId.orElseThrow()
            );
        } else {
            resolvedGraphId.ifPresent(graphId ->
                    activateGraph(graphId, currentContext, eventTime));
        }

        /*
         * What this record means for the visit is the shared policy's answer,
         * asked once. The graph still owns the episode, its timeline and its
         * persistence — but "is this an arrival", "is this a session restore",
         * "does the restore describe a system this visit is not of" and "is the
         * session over" are the same four questions the observer's novelty
         * memory asks, and they were answered separately in both places. The
         * vessel is described by the resolved graph id rather than by raw
         * canonical state, because a graph's identity is exactly a Commander
         * and a positive ship id; the switch above has already run, so a vessel
         * change is never what this transition reports here.
         */
        SystemVisitTransition visit = SystemVisitPolicy.of(
                payload,
                new SystemVisitState(
                        currentEpisode() != null,
                        currentEpisode() == null
                                ? null
                                : currentEpisode().systemAddress(),
                        activeGraphId == null
                                ? null
                                : activeGraphId.commanderFid(),
                        activeGraphId == null
                                ? null
                                : activeGraphId.shipId(),
                        currentContext.systemAddress(),
                        resolvedGraphId.map(GraphId::commanderFid).orElse(null),
                        resolvedGraphId.map(GraphId::shipId).orElse(null)
                )
        );

        if (pendingLocation != null
                && resolvedGraphId.isPresent()
                && currentEpisode() == null
                && !visit.statesWhereTheShipIs()) {
            PendingLocation restore = pendingLocation;
            pendingLocation = null;
            startRestoredEpisode(
                    restore.observation(),
                    restore.eventTime()
            );
        }

        if (visit.arrival()) {
            pendingLocation = null;
            if (resolvedGraphId.isEmpty()) {
                diagnoseMissingIdentity(observation, "FSDJump");
                return applyResult(
                        observation,
                        BehaviorGraphApplyStatus.NO_GRAPH_ID,
                        before
                );
            }
            startJournalRoot(
                    observation,
                    source,
                    eventTime,
                    EpisodeEntrySource.FSD_JUMP,
                    EpisodeCompletionReason.NEXT_SYSTEM
            );
            return applyResult(
                    observation,
                    BehaviorGraphApplyStatus.APPLIED,
                    before
            );
        }

        if (visit.restore()) {
            if (resolvedGraphId.isEmpty()) {
                pendingLocation = new PendingLocation(
                        observation,
                        source,
                        eventTime
                );
                diagnoseMissingIdentity(observation, "Location");
                return applyResult(
                        observation,
                        BehaviorGraphApplyStatus.NO_GRAPH_ID,
                        before
                );
            }
            if (visit.begins()) {
                startRestoredEpisode(observation, eventTime);
            }
            persistMetadataIfNeeded(payload);
            return applyResult(
                    observation,
                    BehaviorGraphApplyStatus.APPLIED,
                    before
            );
        }

        if (visit.sessionEnd()) {
            completeCurrentEpisode(
                    EpisodeCompletionReason.SHUTDOWN,
                    eventTime
            );
            saveAllLoadedGraphs();
            return applyResult(
                    observation,
                    BehaviorGraphApplyStatus.APPLIED,
                    before
            );
        }

        if (resolvedGraphId.isEmpty()) {
            return applyResult(
                    observation,
                    BehaviorGraphApplyStatus.NO_GRAPH_ID,
                    before
            );
        }

        EventSignificance classification =
                significancePolicy.classify(payload);
        if (classification == EventSignificance.NOISE
                || classification == EventSignificance.CONTEXT
                || classification == EventSignificance.BOUNDARY) {
            persistMetadataIfNeeded(payload);
            return applyResult(
                    observation,
                    BehaviorGraphApplyStatus.NOT_APPLICABLE,
                    before
            );
        }

        SystemEpisode currentEpisode = currentEpisode();
        if (currentEpisode == null
                || !currentEpisode.graphId().equals(activeGraphId)) {
            LOGGER.debug(
                    "BEHAVIOR_EVENT_WITHOUT_ACTIVE_EPISODE observationId={} "
                            + "eventType={} graphId={}",
                    observation.observationId(),
                    payload.getClass().getSimpleName(),
                    activeGraphId == null
                            ? "<unknown>"
                            : activeGraphId.canonicalValue()
            );
            return applyResult(
                    observation,
                    BehaviorGraphApplyStatus.NOT_APPLICABLE,
                    before
            );
        }

        recordSignificantOccurrence(
                observation,
                source,
                eventTime,
                occurrenceContext
        );
        return applyResult(
                observation,
                BehaviorGraphApplyStatus.APPLIED,
                before
        );
    }

    public synchronized BehaviorGraphApplyResult completeReplay(
            PublishedObservation<?> observation,
            CurrentGameStateSnapshot currentState
    ) {
        requireOpen();
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(currentState, "currentState");
        requireIncreasingBusSequence(observation);
        currentContext = contextAdapter.toContextSnapshot(currentState);
        GraphCommitMarker before = graphCommitMarker();
        GraphId replayGraphId = activeGraphId;
        completeCurrentEpisode(
                EpisodeCompletionReason.REPLAY_COMPLETED,
                latestEventTime
        );
        saveAllLoadedGraphs();
        if (replayGraphId != null) {
            notifyListener(new ReplayCompleted(
                    replayGraphId,
                    latestEventTime
            ));
        }
        return applyResult(
                observation,
                replayGraphId == null
                        ? BehaviorGraphApplyStatus.NOT_APPLICABLE
                        : BehaviorGraphApplyStatus.APPLIED,
                before
        );
    }

    /**
     * Applies one deterministic batch derived from an ordered Status snapshot.
     *
     * <p>The snapshot itself is technical state. Only explicit changes emitted
     * by {@code StatusStateDeltaAdapter} become behavior occurrences.</p>
     */
    public synchronized BehaviorGraphApplyResult onStatusDeltas(
            PublishedObservation<StatusSnapshotObservation> observation,
            StatusDeltaBatch batch,
            CurrentGameStateSnapshot currentState
    ) {
        requireOpen();
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(currentState, "currentState");
        requireIncreasingBusSequence(observation);
        currentContext = contextAdapter.toContextSnapshot(currentState);
        GraphCommitMarker before = graphCommitMarker();

        SourceReference source = statusSourceReference(observation);
        if (source.sourceSequence() != batch.snapshotSequence()) {
            throw new IllegalArgumentException(
                    "status delta batch source sequence does not match "
                            + "the observation"
            );
        }
        if (batch.deltas().isEmpty()) {
            return applyResult(
                    observation,
                    BehaviorGraphApplyStatus.NOT_APPLICABLE,
                    before
            );
        }
        if (activeGraphId == null
                || currentEpisode() == null
                || !currentEpisode().graphId().equals(activeGraphId)) {
            LOGGER.debug(
                    "BEHAVIOR_STATUS_DELTA_WITHOUT_ACTIVE_EPISODE "
                            + "observationId={} deltaCount={}",
                    observation.observationId(),
                    batch.deltas().size()
            );
            return applyResult(
                    observation,
                    BehaviorGraphApplyStatus.NOT_APPLICABLE,
                    before
            );
        }

        ContextSnapshot context = currentContext();
        for (StatusStateDelta delta : batch.deltas()) {
            NormalizedBehaviorEvent normalized = delta.normalizedEvent();
            Instant eventTime = normalized.timestamp();
            if (eventTime.isAfter(latestEventTime)) {
                latestEventTime = eventTime;
            }
            recordNormalizedOccurrence(
                    BehaviorGraphIds.statusOccurrence(
                            activeGraphId,
                            observation.observationId(),
                            normalized.eventType()
                    ),
                    source,
                    EventOccurrenceSource.STATUS,
                    normalized,
                    context
            );
        }
        return applyResult(
                observation,
                BehaviorGraphApplyStatus.APPLIED,
                before
        );
    }

    public synchronized void closeSource() {
        if (closed) {
            return;
        }
        completeCurrentEpisode(
                EpisodeCompletionReason.SOURCE_CLOSED,
                latestEventTime
        );
        saveAllLoadedGraphs();
        closed = true;
        eventBus.close();
    }

    public synchronized BehaviorGraphApplyResult onNotApplicable(
            PublishedObservation<?> observation,
            CurrentGameStateSnapshot currentState
    ) {
        requireOpen();
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(currentState, "currentState");
        requireIncreasingBusSequence(observation);
        currentContext = contextAdapter.toContextSnapshot(currentState);
        return applyResult(
                observation,
                BehaviorGraphApplyStatus.NOT_APPLICABLE,
                graphCommitMarker()
        );
    }

    public BehaviorGraphEventSource eventSource() {
        return eventBus;
    }

    public synchronized Optional<GraphId> currentGraphId() {
        return Optional.ofNullable(activeGraphId);
    }

    public synchronized Optional<ShipBehaviorGraph> graph(GraphId graphId) {
        return registry.find(Objects.requireNonNull(graphId, "graphId"));
    }

    synchronized Optional<BehaviorGraphVisualizationSnapshot>
            visualizationSnapshot(
                    GraphId graphId,
                    Instant evaluationTime
            ) {
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(evaluationTime, "evaluationTime");
        Optional<ShipBehaviorGraph> found = registry.find(graphId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ShipBehaviorGraph graph = found.orElseThrow();
        GraphRevision revision = revisionFor(graphId);
        Optional<SystemEpisode> episode = activeEpisode(graphId);
        GraphCursor graphCursor = visualizationCursor(
                cursor(graphId).orElse(null),
                graphId,
                episode
        );
        Optional<NormalizedEventType> currentEventType =
                graphCursor == null
                        ? Optional.empty()
                        : Optional.of(graphCursor.eventType());
        Optional<EventOccurrenceId> currentOccurrenceId =
                graphCursor == null
                        ? Optional.empty()
                        : Optional.of(graphCursor.occurrenceId());
        Optional<SystemEpisodeId> activeEpisodeId =
                episode.map(SystemEpisode::id);
        Map<NormalizedEventType, Long> activeOccurrenceCounts =
                activeEpisodeOccurrenceCounts(episode);
        List<BehaviorGraphVisualizationSnapshot.VisualizationNode> nodes =
                graph.nodes().stream()
                        .map(node -> new BehaviorGraphVisualizationSnapshot
                                .VisualizationNode(
                                        node.eventType(),
                                        DISPLAY_NAME_RESOLVER.resolve(
                                                node.eventType()
                                        ),
                                        activeOccurrenceCounts.getOrDefault(
                                                node.eventType(),
                                                0L
                                        )
                                ))
                        .toList();
        List<BehaviorGraphVisualizationSnapshot.VisualizationEdge> edges =
                graph.edges().stream()
                        .map(edge -> new BehaviorGraphVisualizationSnapshot
                                .VisualizationEdge(
                                        edge.key().fromEventType(),
                                        edge.key().toEventType(),
                                        edge.globalCounter().rawCount(),
                                        edge.globalCounter().valueAt(
                                                evaluationTime,
                                                halfLife
                                        )
                                ))
                        .toList();
        return Optional.of(new BehaviorGraphVisualizationSnapshot(
                graph.graphId(),
                shipDisplayName(graph),
                revision.graphVersion(),
                revision.topologyVersion(),
                evaluationTime,
                currentEventType,
                currentOccurrenceId,
                activeEpisodeId,
                nodes,
                edges
        ));
    }

    synchronized ActiveEpisodeNodeOccurrencesSnapshot
            activeEpisodeOccurrences(
                    GraphId graphId,
                    NormalizedEventType eventType
            ) {
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(eventType, "eventType");
        GraphRevision revision = revisionFor(graphId);
        Optional<SystemEpisode> episode = activeEpisode(graphId);
        if (episode.isEmpty()) {
            return new ActiveEpisodeNodeOccurrencesSnapshot(
                    graphId,
                    Optional.empty(),
                    eventType,
                    DISPLAY_NAME_RESOLVER.resolve(eventType),
                    revision.graphVersion(),
                    0,
                    List.of()
            );
        }

        SystemEpisode active = episode.orElseThrow();
        List<EventOccurrenceSummary> occurrences = active.timeline().stream()
                .filter(occurrence ->
                        occurrence.eventType().equals(eventType))
                .sorted(EventOccurrence.EPISODE_ORDER)
                .map(BehaviorGraphService::occurrenceSummary)
                .toList();
        return new ActiveEpisodeNodeOccurrencesSnapshot(
                graphId,
                Optional.of(active.id()),
                eventType,
                DISPLAY_NAME_RESOLVER.resolve(eventType),
                revision.graphVersion(),
                active.timeline().size(),
                occurrences
        );
    }

    synchronized Optional<EventOccurrenceDetailsSnapshot>
            activeEpisodeOccurrenceDetails(
                    GraphId graphId,
                    SystemEpisodeId episodeId,
                    EventOccurrenceId occurrenceId
            ) {
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(episodeId, "episodeId");
        Objects.requireNonNull(occurrenceId, "occurrenceId");
        return activeEpisode(graphId)
                .filter(episode -> episode.id().equals(episodeId))
                .flatMap(episode -> episode.timeline().stream()
                        .filter(occurrence ->
                                occurrence.id().equals(occurrenceId))
                        .findFirst())
                .map(BehaviorGraphService::occurrenceDetails);
    }

    public synchronized Optional<GraphCursor> cursor(GraphId graphId) {
        Objects.requireNonNull(graphId, "graphId");
        if (replayProjection != null
                && replayProjection.visibleEpisode().graphId()
                        .equals(graphId)) {
            return Optional.ofNullable(replayProjection.cursor());
        }
        return graph(graphId).map(ShipBehaviorGraph::cursor);
    }

    public synchronized Optional<SystemEpisode> activeEpisode(GraphId graphId) {
        Objects.requireNonNull(graphId, "graphId");
        if (replayProjection != null
                && replayProjection.visibleEpisode().graphId()
                        .equals(graphId)) {
            return Optional.of(replayProjection.visibleEpisode());
        }
        if (activeEpisode != null
                && activeEpisode.graphId().equals(graphId)
                && activeEpisode.active()) {
            return Optional.of(activeEpisode);
        }
        return store.loadActiveEpisode(graphId);
    }

    public synchronized List<SystemEpisode> episodes(GraphId graphId) {
        Objects.requireNonNull(graphId, "graphId");
        List<SystemEpisode> episodes = new ArrayList<>(
                store.listEpisodes(graphId)
        );
        SystemEpisode currentEpisode = currentEpisode();
        if (currentEpisode != null
                && currentEpisode.graphId().equals(graphId)) {
            episodes.removeIf(episode ->
                    episode.id().equals(currentEpisode.id()));
            episodes.add(currentEpisode);
        }
        episodes.sort(
                Comparator.comparing(SystemEpisode::startedAt)
                        .thenComparing(SystemEpisode::id)
        );
        return List.copyOf(episodes);
    }

    public synchronized ContextSnapshot currentContext() {
        return currentContext;
    }

    public synchronized List<TransitionEdgeView> outgoingEdges(
            GraphId graphId,
            NormalizedEventType eventType,
            Instant evaluationTime
    ) {
        return graph(graphId)
                .map(graph -> probabilityCalculator.outgoingEdges(
                        graph,
                        eventType,
                        evaluationTime
                ))
                .orElseGet(List::of);
    }

    public synchronized List<NextEventPrediction> predictNext(
            GraphId graphId,
            ContextSnapshot currentContext,
            Instant evaluationTime,
            int limit
    ) {
        Optional<ShipBehaviorGraph> graph = graph(graphId);
        Optional<GraphCursor> cursor = cursor(graphId);
        if (graph.isEmpty() || cursor.isEmpty()) {
            return List.of();
        }
        List<NextEventPrediction> predictions =
                probabilityCalculator.predict(
                        graph.orElseThrow(),
                        cursor.orElseThrow(),
                        currentContext,
                        evaluationTime,
                        limit
                );
        if (!predictions.isEmpty()) {
            notifyListener(new NextEventPredictionChanged(
                    graphId,
                    cursor.orElseThrow().occurrenceId(),
                    evaluationTime
            ));
            LOGGER.debug(
                    "BEHAVIOR_PREDICTION graphId={} currentType={} "
                            + "candidateCount={} topProbability={}",
                    graphId.canonicalValue(),
                    cursor.orElseThrow().eventType(),
                    predictions.size(),
                    predictions.getFirst().probability()
            );
        }
        return predictions;
    }

    synchronized BehaviorSituationSnapshot captureSituation(
            PublishedObservation<?> trigger,
            CurrentGameStateSnapshot currentState,
            BehaviorGraphApplyResult applyResult
    ) {
        requireOpen();
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(currentState, "currentState");
        Objects.requireNonNull(applyResult, "applyResult");
        if (trigger.busSequence() != applyResult.busSequence()) {
            throw inconsistent(
                    "trigger busSequence does not match apply result"
            );
        }
        if (applyResult.status() == BehaviorGraphApplyStatus.NO_GRAPH_ID) {
            return BehaviorSituationSnapshot.unavailable(
                    applyResult,
                    BehaviorSituationCaptureStatus.NO_GRAPH_ID
            );
        }
        if (applyResult.status() == BehaviorGraphApplyStatus.DISABLED
                || applyResult.status() == BehaviorGraphApplyStatus.FAILED) {
            throw inconsistent(
                    "terminal graph failure status cannot be queried"
            );
        }
        if (activeGraphId == null) {
            requireEmptyGraphMetadata(applyResult);
            if (applyResult.status()
                    != BehaviorGraphApplyStatus.NOT_APPLICABLE) {
                throw inconsistent(
                        "missing active graph for apply status "
                                + applyResult.status()
                );
            }
            return BehaviorSituationSnapshot.unavailable(
                    applyResult,
                    BehaviorSituationCaptureStatus.NO_ACTIVE_GRAPH
            );
        }

        GraphId graphId = requireExpectedGraph(
                applyResult,
                currentState
        );
        GraphRevision revision = graphRevisions.getOrDefault(
                graphId,
                GraphRevision.INITIAL
        );
        requireExpectedRevision(applyResult, revision);
        Optional<SystemEpisode> episode = activeEpisode(graphId);
        Optional<GraphCursor> graphCursor = cursor(graphId);
        if (episode.isEmpty()) {
            if (applyResult.activeEpisodeId().isPresent()
                    || applyResult.cursor().isPresent()
                    || graphCursor.isPresent()) {
                throw inconsistent(
                        "apply result claims an unavailable active episode"
                );
            }
            return BehaviorSituationSnapshot.unavailable(
                    applyResult,
                    BehaviorSituationCaptureStatus.NO_ACTIVE_EPISODE
            );
        }
        if (episode.orElseThrow().awaitingFirstOccurrence()) {
            /*
             * A restored visit that has recorded nothing has no position to
             * report: no current occurrence, no trajectory, nothing to predict
             * from. Reported as unavailable rather than invented.
             */
            if (graphCursor.isPresent() || applyResult.cursor().isPresent()) {
                throw inconsistent(
                        "an empty restored episode cannot carry a cursor"
                );
            }
            return BehaviorSituationSnapshot.unavailable(
                    applyResult,
                    BehaviorSituationCaptureStatus.NO_ACTIVE_EPISODE
            );
        }
        if (graphCursor.isEmpty()) {
            throw inconsistent(
                    "active episode has no graph cursor"
            );
        }

        SystemEpisode active = episode.orElseThrow();
        GraphCursor cursor = graphCursor.orElseThrow();
        requireExpectedEpisodeAndCursor(applyResult, active, cursor);
        EventOccurrence currentOccurrence = requireCurrentOccurrence(
                active,
                cursor
        );
        List<SituationOccurrence> trajectory =
                active.timeline().stream()
                        .map(occurrence -> situationOccurrence(
                                occurrence,
                                occurrence.id().equals(cursor.occurrenceId())
                        ))
                        .toList();
        Map<NormalizedEventType, Long> occurrenceCounts =
                activeEpisodeOccurrenceCounts(Optional.of(active));
        ActiveEpisodeSituation activeSituation =
                new ActiveEpisodeSituation(
                        graphId,
                        active.id(),
                        active.systemAddress(),
                        active.systemName(),
                        active.startedAt(),
                        cursor,
                        trajectory,
                        situationOccurrence(currentOccurrence, true),
                        trajectory.size(),
                        occurrenceCounts,
                        revision.graphVersion(),
                        revision.topologyVersion()
                );
        return BehaviorSituationSnapshot.available(
                applyResult,
                activeSituation,
                situationPredictions(
                        graphId,
                        cursor,
                        currentState
                )
        );
    }

    /**
     * One accepted occurrence, projected into the situation view.
     *
     * <p>Provenance and body are both carried rather than derived. The body
     * comes from the context snapshot the occurrence was accepted with, so it
     * is whatever the graph had established at that moment — a later arrival
     * never rewrites where an earlier occurrence happened, and a body is never
     * inferred from a name.</p>
     */
    private static SituationOccurrence situationOccurrence(
            EventOccurrence occurrence,
            boolean current
    ) {
        ContextSnapshot context = occurrence.context();
        BodyIdentity body =
                context.systemAddress() == null || context.bodyId() == null
                        ? null
                        : new BodyIdentity(
                                context.systemAddress(),
                                context.bodyId()
                        );
        return new SituationOccurrence(
                occurrence.id(),
                occurrence.episodeSequence(),
                occurrence.eventType(),
                // Carried, never derived from the type.
                occurrence.source(),
                occurrence.timestamp(),
                current,
                body
        );
    }

    public synchronized ShipBehaviorGraphSummary summary(GraphId graphId) {
        ShipBehaviorGraph graph = graph(graphId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown graph: " + graphId.canonicalValue()
                ));
        long totalOccurrences = graph.nodes().stream()
                .mapToLong(node -> node.rawOccurrenceCount())
                .sum();
        return new ShipBehaviorGraphSummary(
                graph.graphId(),
                graph.shipType(),
                graph.shipName(),
                graph.loadoutHash(),
                graph.nodes().size(),
                graph.edges().size(),
                graph.episodes().size(),
                totalOccurrences,
                cursor(graphId).orElse(null)
        );
    }

    private void switchShip(
            PublishedObservation<? extends JournalEventObservation> observation,
            SourceReference source,
            Instant eventTime,
            ContextSnapshot currentContext,
            GraphId newGraphId
    ) {
        boolean hadPreviousGraph = activeGraphId != null;
        if (hadPreviousGraph && !activeGraphId.equals(newGraphId)) {
            completeCurrentEpisode(
                    EpisodeCompletionReason.SHIP_SWITCH,
                    eventTime
            );
        }
        activateGraph(newGraphId, currentContext, eventTime);
        if (currentEpisode() != null) {
            completeCurrentEpisode(
                    EpisodeCompletionReason.SHIP_SWITCH,
                    eventTime
            );
        }
        if (!hadPreviousGraph
                || currentContext.systemAddress() == null) {
            return;
        }

        EventOccurrenceId rootId = BehaviorGraphIds.shipSwitchOccurrence(
                observation.observationId(),
                newGraphId
        );
        if (occurrenceAlreadyRecorded(
                rootId,
                newGraphId,
                source,
                NormalizedEventType.SYSTEM_ENTRY,
                "ShipSwitch"
        )) {
            if (observation.captureMode() == ObservationCaptureMode.REPLAY) {
                startReplayProjection(rootId, newGraphId, eventTime);
            }
            return;
        }
        SystemEpisodeId episodeId = BehaviorGraphIds.episode(
                newGraphId,
                rootId,
                EpisodeEntrySource.SHIP_SWITCH
        );
        Map<String, JsonNode> attributes = new TreeMap<>();
        attributes.put(
                "SystemAddress",
                JsonNodeFactory.instance.numberNode(
                        currentContext.systemAddress()
                )
        );
        if (currentContext.systemName() != null) {
            attributes.put(
                    "StarSystem",
                    JsonNodeFactory.instance.textNode(
                            currentContext.systemName()
                    )
            );
        }
        EventOccurrence root = new EventOccurrence(
                rootId,
                newGraphId,
                episodeId,
                0,
                NormalizedEventType.SYSTEM_ENTRY,
                "ShipSwitch",
                // Kairon minted this root; there is no originating external
                // observation of its own.
                EventOccurrenceSource.SYNTHETIC,
                eventTime,
                source.sourceSequence(),
                source.sourceId(),
                attributes,
                currentContext
        );
        beginEpisode(
                root,
                currentContext.systemAddress(),
                systemName(currentContext),
                EpisodeEntrySource.SHIP_SWITCH
        );
    }

    /**
     * Opens the visit a restoring {@code Location} describes, recording nothing.
     *
     * <p>The Commander is already here. There is no arrival to count, no node
     * to advance and no edge to learn: the first structural event of this visit
     * follows nothing, and saying otherwise would teach the graph a transition
     * out of a session restart.</p>
     */
    private void startRestoredEpisode(
            PublishedObservation<? extends JournalEventObservation> observation,
            Instant eventTime
    ) {
        ContextSnapshot context = currentContext();
        if (activeGraphId == null || context.systemAddress() == null) {
            diagnoseMissingIdentity(
                    observation,
                    EpisodeEntrySource.LOCATION_RESTORE.name()
            );
            return;
        }
        SystemEpisodeId episodeId = BehaviorGraphIds.restoredEpisode(
                activeGraphId,
                observation.observationId()
        );
        if (activeEpisode != null && activeEpisode.id().equals(episodeId)) {
            return;
        }
        Optional<SystemEpisode> persisted = store.loadEpisode(episodeId);
        if (persisted.isPresent()) {
            if (observation.captureMode() == ObservationCaptureMode.REPLAY) {
                startRestoredReplayProjection(
                        persisted.orElseThrow(),
                        eventTime
                );
                return;
            }
            completeCurrentEpisode(
                    EpisodeCompletionReason.NEXT_SYSTEM,
                    eventTime
            );
            activeEpisode = activeCopy(persisted.orElseThrow());
            return;
        }
        completeCurrentEpisode(
                EpisodeCompletionReason.NEXT_SYSTEM,
                eventTime
        );
        beginRestoredEpisode(
                episodeId,
                context.systemAddress(),
                systemName(context),
                eventTime
        );
    }

    private void startJournalRoot(
            PublishedObservation<? extends JournalEventObservation> observation,
            SourceReference source,
            Instant eventTime,
            EpisodeEntrySource entrySource,
            EpisodeCompletionReason previousCompletionReason
    ) {
        ContextSnapshot context = currentContext();
        if (activeGraphId == null || context.systemAddress() == null) {
            diagnoseMissingIdentity(observation, entrySource.name());
            return;
        }
        NormalizedBehaviorEvent normalized =
                normalizer.normalizeSystemEntry(
                        observation.payload(),
                        eventTime
                );
        EventOccurrenceId rootId = BehaviorGraphIds.journalOccurrence(
                activeGraphId,
                observation.observationId()
        );
        if (occurrenceAlreadyRecorded(
                rootId,
                activeGraphId,
                source,
                NormalizedEventType.SYSTEM_ENTRY,
                normalized.originalEventName()
        )) {
            if (observation.captureMode() == ObservationCaptureMode.REPLAY) {
                startReplayProjection(rootId, activeGraphId, eventTime);
            }
            return;
        }
        completeCurrentEpisode(previousCompletionReason, eventTime);
        SystemEpisodeId episodeId = BehaviorGraphIds.episode(
                activeGraphId,
                rootId,
                entrySource
        );
        EventOccurrence root = new EventOccurrence(
                rootId,
                activeGraphId,
                episodeId,
                0,
                NormalizedEventType.SYSTEM_ENTRY,
                normalized.originalEventName(),
                EventOccurrenceSource.JOURNAL,
                eventTime,
                source.sourceSequence(),
                source.sourceId(),
                normalized.attributes(),
                context
        );
        beginEpisode(
                root,
                context.systemAddress(),
                systemName(context),
                entrySource
        );
    }

    /**
     * Installs an empty restored episode and clears the cursor.
     *
     * <p>The cleared cursor is the point: with no occurrence in this visit
     * there is nothing for the next event to have followed, and a stale cursor
     * left pointing into the previous episode is exactly how a cross-episode
     * edge would be minted.</p>
     */
    private void beginRestoredEpisode(
            SystemEpisodeId episodeId,
            long systemAddress,
            String systemName,
            Instant restoredAt
    ) {
        replayProjection = null;
        ShipBehaviorGraph graph = requiredActiveGraph();
        activeEpisode = SystemEpisode.startRestored(
                episodeId,
                graph.graphId(),
                systemAddress,
                systemName,
                restoredAt
        );
        graph = graph.withEpisode(activeEpisode).withCursor(null);
        registry.replace(graph);
        significantEventsSinceSnapshot = 0;
        persistActive(graph);
        advanceRevision(graph.graphId(), false);

        LOGGER.info(
                "BEHAVIOR_EPISODE_RESTORED graphId={} episodeId={} "
                        + "systemAddress={}",
                graph.graphId().canonicalValue(),
                activeEpisode.id(),
                activeEpisode.systemAddress()
        );
        notifyListener(new SystemEpisodeStarted(
                graph.graphId(),
                activeEpisode.id(),
                restoredAt
        ));
        notifyListener(new GraphCursorChanged(
                graph.graphId(),
                Optional.empty(),
                restoredAt
        ));
        notifyListener(new BehaviorGraphUpdated(
                graph.graphId(),
                restoredAt
        ));
    }

    /**
     * Re-projects an already recorded restored episode during replay.
     *
     * <p>The same progressive exposure a rooted episode gets, minus the root:
     * the visible episode starts empty and each recorded occurrence appears
     * only once its own observation has passed through this run.</p>
     */
    private void startRestoredReplayProjection(
            SystemEpisode persistedEpisode,
            Instant eventTime
    ) {
        if (replayProjection != null
                && replayProjection.sourceEpisode().id()
                        .equals(persistedEpisode.id())) {
            return;
        }
        if (activeEpisode != null
                && activeEpisode.id().equals(persistedEpisode.id())) {
            activeEpisode = null;
        } else {
            completeCurrentEpisode(
                    EpisodeCompletionReason.NEXT_SYSTEM,
                    eventTime
            );
        }
        SystemEpisode visibleEpisode = SystemEpisode.startRestored(
                persistedEpisode.id(),
                persistedEpisode.graphId(),
                persistedEpisode.systemAddress(),
                persistedEpisode.systemName(),
                persistedEpisode.startedAt()
        );
        replayProjection = new ReplayEpisodeProjection(
                persistedEpisode,
                visibleEpisode,
                null
        );
        significantEventsSinceSnapshot = 0;
        advanceRevision(persistedEpisode.graphId(), false);

        LOGGER.info(
                "BEHAVIOR_REPLAY_RESTORED_EPISODE_PROJECTED graphId={} "
                        + "episodeId={} persistedOccurrences={}",
                persistedEpisode.graphId().canonicalValue(),
                persistedEpisode.id(),
                persistedEpisode.timeline().size()
        );
        notifyListener(new SystemEpisodeStarted(
                persistedEpisode.graphId(),
                persistedEpisode.id(),
                persistedEpisode.startedAt()
        ));
        notifyListener(new GraphCursorChanged(
                persistedEpisode.graphId(),
                Optional.empty(),
                persistedEpisode.startedAt()
        ));
        notifyListener(new BehaviorGraphUpdated(
                persistedEpisode.graphId(),
                persistedEpisode.startedAt()
        ));
    }

    private void beginEpisode(
            EventOccurrence root,
            long systemAddress,
            String systemName,
            EpisodeEntrySource entrySource
    ) {
        replayProjection = null;
        ShipBehaviorGraph graph = requiredActiveGraph();
        activeEpisode = SystemEpisode.startWithRoot(
                root.episodeId(),
                root.graphId(),
                systemAddress,
                systemName,
                entrySource,
                root
        );
        boolean nodeWasNew = graph.nodes().stream().noneMatch(node ->
                node.eventType().equals(root.eventType()));
        graph = graph.recordOccurrence(root)
                .withEpisode(activeEpisode)
                .withCursor(cursorFor(root));
        registry.replace(graph);
        occurrencesSeenInProcess.add(root.id());
        significantEventsSinceSnapshot = 0;
        persistActive(graph);
        advanceRevision(graph.graphId(), nodeWasNew);

        LOGGER.info(
                "BEHAVIOR_EPISODE_STARTED graphId={} episodeId={} "
                        + "systemAddress={} entrySource={}",
                graph.graphId().canonicalValue(),
                activeEpisode.id(),
                activeEpisode.systemAddress(),
                entrySource
        );
        if (nodeWasNew) {
            notifyListener(new EventTypeNodeCreated(
                    graph.graphId(),
                    root.eventType(),
                    root.timestamp()
            ));
        }
        notifyListener(new SystemEpisodeStarted(
                graph.graphId(),
                activeEpisode.id(),
                root.timestamp()
        ));
        notifyListener(new EventOccurrenceRecorded(
                graph.graphId(),
                root.id(),
                root.timestamp()
        ));
        notifyListener(new GraphCursorChanged(
                graph.graphId(),
                Optional.of(graph.cursor()),
                root.timestamp()
        ));
        notifyListener(new BehaviorGraphUpdated(
                graph.graphId(),
                root.timestamp()
        ));
    }

    private void recordSignificantOccurrence(
            PublishedObservation<? extends JournalEventObservation> observation,
            SourceReference source,
            Instant eventTime,
            ContextSnapshot context
    ) {
        EventOccurrenceId occurrenceId = BehaviorGraphIds.journalOccurrence(
                activeGraphId,
                observation.observationId()
        );
        NormalizedBehaviorEvent normalized = normalizer.normalize(
                observation.payload(),
                eventTime
        );
        if (occurrenceAlreadyRecorded(
                occurrenceId,
                activeGraphId,
                source,
                normalized.eventType(),
                normalized.originalEventName()
        )) {
            advanceReplayProjection(occurrenceId);
            return;
        }
        SystemEpisode currentEpisode = currentEpisode();
        if (currentEpisode == null) {
            return;
        }
        EventOccurrence previous = currentEpisode.awaitingFirstOccurrence()
                ? null
                : currentEpisode.timeline().getLast();
        if (!occurrenceProjectionPolicy.shouldRecord(previous, normalized)) {
            occurrencesSeenInProcess.add(occurrenceId);
            LOGGER.debug(
                    "BEHAVIOR_OCCURRENCE_PROJECTED_INTO_CURRENT_RUN "
                            + "graphId={} episodeId={} observationId={} "
                            + "eventType={} sourceSequence={}",
                    activeGraphId.canonicalValue(),
                    currentEpisode.id(),
                    observation.observationId(),
                    normalized.eventType(),
                    source.sourceSequence()
            );
            return;
        }
        if (!routeTargetPolicy.shouldRecord(
                lastOccurrenceOfType(currentEpisode, normalized.eventType()),
                normalized
        )) {
            occurrencesSeenInProcess.add(occurrenceId);
            LOGGER.debug(
                    "BEHAVIOR_ROUTE_TARGET_UNCHANGED graphId={} episodeId={} "
                            + "observationId={} eventType={} "
                            + "sourceSequence={}",
                    activeGraphId.canonicalValue(),
                    currentEpisode.id(),
                    observation.observationId(),
                    normalized.eventType(),
                    source.sourceSequence()
            );
            return;
        }
        /*
         * Capture mode reaches the decision because a scanner finding is the
         * one structural kind whose recording decides whether a later
         * observation is a finding at all. A historical result must not become
         * the occurrence that the live reading repeating it is deduplicated
         * against; refusing here, before any occurrence exists, is what keeps
         * the occurrence and the model-facing event on the same observation.
         */
        if (!bodySurveyPolicy.shouldRecord(
                currentEpisode.timeline(),
                normalized,
                observation.captureMode()
        )) {
            occurrencesSeenInProcess.add(occurrenceId);
            LOGGER.debug(
                    "BEHAVIOR_BODY_SURVEY_NOT_A_NEW_RESULT graphId={} "
                            + "episodeId={} observationId={} eventType={} "
                            + "captureMode={} sourceSequence={}",
                    activeGraphId.canonicalValue(),
                    currentEpisode.id(),
                    observation.observationId(),
                    normalized.eventType(),
                    observation.captureMode(),
                    source.sourceSequence()
            );
            return;
        }
        recordNormalizedOccurrence(
                occurrenceId,
                source,
                EventOccurrenceSource.JOURNAL,
                normalized,
                context
        );
    }

    /**
     * The last occurrence of one type in this episode, or null.
     *
     * <p>Deliberately not the immediately preceding occurrence: a route target
     * restated after a jump began is still a restatement of the same target,
     * and what stands between the two records says nothing about whether they
     * describe the same state.</p>
     */
    private static EventOccurrence lastOccurrenceOfType(
            SystemEpisode episode,
            NormalizedEventType eventType
    ) {
        List<EventOccurrence> timeline = episode.timeline();
        for (int index = timeline.size() - 1; index >= 0; index--) {
            EventOccurrence occurrence = timeline.get(index);
            if (occurrence.eventType().equals(eventType)) {
                return occurrence;
            }
        }
        return null;
    }

    /**
     * Appends one accepted occurrence to the active episode.
     *
     * <p>{@code occurrenceSource} is provenance supplied by the caller that
     * accepted the observation. It is never inferred here from the normalized
     * type: a journal event and a Status delta can normalize identically.</p>
     */
    private void recordNormalizedOccurrence(
            EventOccurrenceId occurrenceId,
            SourceReference source,
            EventOccurrenceSource occurrenceSource,
            NormalizedBehaviorEvent normalized,
            ContextSnapshot context
    ) {
        Objects.requireNonNull(occurrenceSource, "occurrenceSource");
        if (occurrenceAlreadyRecorded(
                occurrenceId,
                activeGraphId,
                source,
                normalized.eventType(),
                normalized.originalEventName()
        )) {
            advanceReplayProjection(occurrenceId);
            return;
        }
        promoteReplayProjectionForAppend();
        EventOccurrence occurrence = new EventOccurrence(
                occurrenceId,
                activeGraphId,
                activeEpisode.id(),
                activeEpisode.timeline().size(),
                normalized.eventType(),
                normalized.originalEventName(),
                occurrenceSource,
                normalized.timestamp(),
                source.sourceSequence(),
                source.sourceId(),
                normalized.attributes(),
                context
        );

        ShipBehaviorGraph graph = requiredActiveGraph();
        GraphCursor previousCursor = graph.cursor();
        EventOccurrence previous = previousCursor == null
                ? null
                : activeEpisode.occurrence(previousCursor.occurrenceId());
        boolean nodeWasNew = graph.nodes().stream().noneMatch(node ->
                node.eventType().equals(occurrence.eventType()));

        /*
         * A restored episode has no predecessor for its first occurrence. That
         * is not a missing cursor: nothing preceded this event in this visit,
         * so no transition exists to record and the graph learns no edge. Any
         * other absent cursor is still a broken invariant.
         */
        boolean firstOfRestoredEpisode =
                previous == null && activeEpisode.awaitingFirstOccurrence();
        if (!firstOfRestoredEpisode
                && (previous == null
                || !previous.episodeId().equals(activeEpisode.id()))) {
            throw new IllegalStateException(
                    "active episode cursor occurrence is missing"
            );
        }
        OccurrenceTransition transition = null;
        boolean edgeWasNew = false;
        if (!firstOfRestoredEpisode) {
            ContextKey contextKey = contextKeyFactory.create(
                    previous.eventType(),
                    previous.context()
            );
            transition = new OccurrenceTransition(
                    BehaviorGraphIds.transition(
                            activeEpisode.id(),
                            previous.id(),
                            occurrence.id()
                    ),
                    activeEpisode.id(),
                    previous.id(),
                    occurrence.id(),
                    previous.eventType(),
                    occurrence.eventType(),
                    occurrence.timestamp(),
                    contextKey
            );
            EdgeKey edgeKey = new EdgeKey(
                    transition.fromEventType(),
                    transition.toEventType()
            );
            edgeWasNew = graph.edge(edgeKey) == null;
        }
        activeEpisode = activeEpisode.appendOccurrence(
                occurrence,
                transition
        );
        graph = graph.recordOccurrence(occurrence);
        if (transition != null) {
            graph = graph.recordTransition(transition, halfLife);
        }

        GraphCursor cursor = cursorFor(occurrence);
        graph = graph.withEpisode(activeEpisode).withCursor(cursor);
        registry.replace(graph);
        occurrencesSeenInProcess.add(occurrence.id());
        significantEventsSinceSnapshot++;
        advanceRevision(
                graph.graphId(),
                nodeWasNew || edgeWasNew
        );

        LOGGER.debug(
                "BEHAVIOR_OCCURRENCE_RECORDED graphId={} episodeId={} "
                        + "occurrenceId={} eventType={} sourceSequence={}",
                graph.graphId().canonicalValue(),
                activeEpisode.id(),
                occurrence.id(),
                occurrence.eventType(),
                occurrence.sourceSequence()
        );
        if (nodeWasNew) {
            notifyListener(new EventTypeNodeCreated(
                    graph.graphId(),
                    occurrence.eventType(),
                    occurrence.timestamp()
            ));
        }
        if (edgeWasNew) {
            notifyListener(new TransitionEdgeCreated(
                    graph.graphId(),
                    new EdgeKey(
                            transition.fromEventType(),
                            transition.toEventType()
                    ),
                    occurrence.timestamp()
            ));
        }
        notifyListener(new EventOccurrenceRecorded(
                graph.graphId(),
                occurrence.id(),
                occurrence.timestamp()
        ));
        if (transition != null) {
            notifyListener(new OccurrenceTransitionRecorded(
                    graph.graphId(),
                    transition.id(),
                    occurrence.timestamp()
            ));
        }
        notifyListener(new GraphCursorChanged(
                graph.graphId(),
                Optional.of(cursor),
                occurrence.timestamp()
        ));
        notifyListener(new BehaviorGraphUpdated(
                graph.graphId(),
                occurrence.timestamp()
        ));

        if (significantEventsSinceSnapshot
                >= snapshotEverySignificantEvents) {
            persistActive(graph);
            significantEventsSinceSnapshot = 0;
            LOGGER.info(
                    "BEHAVIOR_GRAPH_SNAPSHOT_SAVED graphId={} episodeId={}",
                    graph.graphId().canonicalValue(),
                    activeEpisode.id()
            );
        }
    }

    private SystemEpisode currentEpisode() {
        if (replayProjection != null) {
            return replayProjection.visibleEpisode();
        }
        return activeEpisode;
    }

    private void completeCurrentEpisode(
            EpisodeCompletionReason reason,
            Instant eventTime
    ) {
        if (replayProjection != null) {
            completeReplayProjection(reason, eventTime);
        }
        completeActiveEpisode(reason, eventTime);
    }

    private void startReplayProjection(
            EventOccurrenceId rootOccurrenceId,
            GraphId graphId,
            Instant eventTime
    ) {
        EventOccurrence root = store.findOccurrence(rootOccurrenceId)
                .orElseThrow(() -> new IllegalStateException(
                        "recorded replay root occurrence is missing"
                ));
        SystemEpisode persistedEpisode = store.loadEpisode(root.episodeId())
                .orElseThrow(() -> new IllegalStateException(
                        "recorded replay episode is missing"
                ));
        if (!root.graphId().equals(graphId)
                || !persistedEpisode.graphId().equals(graphId)
                || !persistedEpisode.rootOccurrenceId()
                        .equals(rootOccurrenceId)
                || !persistedEpisode.timeline().getFirst().equals(root)) {
            throw new IllegalStateException(
                    "recorded replay root does not match its episode"
            );
        }
        if (replayProjection != null
                && replayProjection.sourceEpisode().id()
                        .equals(persistedEpisode.id())) {
            return;
        }

        if (activeEpisode != null
                && activeEpisode.id().equals(persistedEpisode.id())) {
            /*
             * The store may contain an active episode recovered after an
             * interrupted run. Replay must expose it progressively, not show
             * the full persisted timeline before those observations have
             * passed through this run.
             */
            activeEpisode = null;
        } else {
            completeCurrentEpisode(
                    EpisodeCompletionReason.NEXT_SYSTEM,
                    eventTime
            );
        }

        SystemEpisode visibleEpisode = SystemEpisode.startWithRoot(
                persistedEpisode.id(),
                persistedEpisode.graphId(),
                persistedEpisode.systemAddress(),
                persistedEpisode.systemName(),
                persistedEpisode.entrySource(),
                root
        );
        GraphCursor cursor = cursorFor(root);
        replayProjection = new ReplayEpisodeProjection(
                persistedEpisode,
                visibleEpisode,
                cursor
        );
        occurrencesSeenInProcess.add(root.id());
        significantEventsSinceSnapshot = 0;
        advanceRevision(graphId, false);

        LOGGER.info(
                "BEHAVIOR_REPLAY_EPISODE_PROJECTED graphId={} episodeId={} "
                        + "persistedOccurrences={}",
                graphId.canonicalValue(),
                persistedEpisode.id(),
                persistedEpisode.timeline().size()
        );
        notifyListener(new SystemEpisodeStarted(
                graphId,
                persistedEpisode.id(),
                root.timestamp()
        ));
        notifyListener(new GraphCursorChanged(
                graphId,
                Optional.of(cursor),
                root.timestamp()
        ));
        notifyListener(new BehaviorGraphUpdated(
                graphId,
                root.timestamp()
        ));
    }

    private void advanceReplayProjection(
            EventOccurrenceId occurrenceId
    ) {
        if (replayProjection == null) {
            return;
        }
        EventOccurrence stored = store.findOccurrence(occurrenceId)
                .orElse(null);
        if (stored == null) {
            return;
        }
        SystemEpisode sourceEpisode = replayProjection.sourceEpisode();
        if (!stored.graphId().equals(sourceEpisode.graphId())
                || !stored.episodeId().equals(sourceEpisode.id())) {
            throw new IllegalStateException(
                    "recorded replay occurrence belongs to another episode"
            );
        }

        int targetIndex = -1;
        for (int index = 0;
                index < sourceEpisode.timeline().size();
                index++) {
            if (sourceEpisode.timeline().get(index).id()
                    .equals(occurrenceId)) {
                targetIndex = index;
                break;
            }
        }
        if (targetIndex < 0) {
            throw new IllegalStateException(
                    "recorded replay occurrence is missing from its episode"
            );
        }

        SystemEpisode visibleEpisode =
                replayProjection.visibleEpisode();
        if (targetIndex < visibleEpisode.timeline().size()) {
            return;
        }
        for (int index = visibleEpisode.timeline().size();
                index <= targetIndex;
                index++) {
            EventOccurrence occurrence =
                    sourceEpisode.timeline().get(index);
            // Index zero of a restored episode followed nothing, so the source
            // episode recorded no transition into it either.
            OccurrenceTransition transition = index == 0
                    ? null
                    : sourceEpisode.occurrenceTransitions().get(index - 1);
            visibleEpisode = visibleEpisode.appendOccurrence(
                    occurrence,
                    transition
            );
            occurrencesSeenInProcess.add(occurrence.id());
        }

        GraphCursor cursor = cursorFor(
                visibleEpisode.timeline().getLast()
        );
        replayProjection = new ReplayEpisodeProjection(
                sourceEpisode,
                visibleEpisode,
                cursor
        );
        advanceRevision(sourceEpisode.graphId(), false);

        LOGGER.debug(
                "BEHAVIOR_REPLAY_PROJECTION_ADVANCED graphId={} "
                        + "episodeId={} occurrenceId={} visibleOccurrences={}",
                sourceEpisode.graphId().canonicalValue(),
                sourceEpisode.id(),
                occurrenceId,
                visibleEpisode.timeline().size()
        );
        notifyListener(new GraphCursorChanged(
                sourceEpisode.graphId(),
                Optional.of(cursor),
                stored.timestamp()
        ));
        notifyListener(new BehaviorGraphUpdated(
                sourceEpisode.graphId(),
                stored.timestamp()
        ));
    }

    private void promoteReplayProjectionForAppend() {
        if (replayProjection == null) {
            return;
        }
        SystemEpisode sourceEpisode = replayProjection.sourceEpisode();
        activeEpisode = activeCopy(sourceEpisode);
        GraphCursor cursor = activeEpisode.awaitingFirstOccurrence()
                ? null
                : cursorFor(activeEpisode.timeline().getLast());
        ShipBehaviorGraph graph = requiredActiveGraph()
                .withEpisode(activeEpisode)
                .withCursor(cursor);
        registry.replace(graph);
        replayProjection = null;
        LOGGER.info(
                "BEHAVIOR_REPLAY_EPISODE_RESUMED graphId={} episodeId={} "
                        + "persistedOccurrences={}",
                graph.graphId().canonicalValue(),
                activeEpisode.id(),
                activeEpisode.timeline().size()
        );
    }

    private void completeReplayProjection(
            EpisodeCompletionReason reason,
            Instant eventTime
    ) {
        ReplayEpisodeProjection completedProjection = replayProjection;
        replayProjection = null;
        SystemEpisode sourceEpisode =
                completedProjection.sourceEpisode();
        Instant lastOccurrenceTime =
                sourceEpisode.awaitingFirstOccurrence()
                        ? sourceEpisode.startedAt()
                        : sourceEpisode.timeline().getLast().timestamp();
        Instant completionTime = eventTime.isBefore(lastOccurrenceTime)
                ? lastOccurrenceTime
                : eventTime;

        if (sourceEpisode.active()) {
            SystemEpisode completedEpisode = sourceEpisode.complete(
                    completionTime,
                    reason
            );
            ShipBehaviorGraph graph = requiredActiveGraph()
                    .withEpisode(completedEpisode)
                    .withCursor(null);
            registry.replace(graph);
            store.saveEpisode(completedEpisode);
            store.saveGraph(graph);
        }
        advanceRevision(sourceEpisode.graphId(), false);
        significantEventsSinceSnapshot = 0;

        LOGGER.info(
                "BEHAVIOR_REPLAY_EPISODE_PROJECTION_COMPLETED graphId={} "
                        + "episodeId={} reason={} visibleOccurrences={}",
                sourceEpisode.graphId().canonicalValue(),
                sourceEpisode.id(),
                reason,
                completedProjection.visibleEpisode().timeline().size()
        );
        notifyListener(new SystemEpisodeCompleted(
                sourceEpisode.graphId(),
                sourceEpisode.id(),
                completionTime
        ));
        notifyListener(new GraphCursorChanged(
                sourceEpisode.graphId(),
                Optional.empty(),
                completionTime
        ));
        notifyListener(new BehaviorGraphUpdated(
                sourceEpisode.graphId(),
                completionTime
        ));
    }

    private static SystemEpisode activeCopy(SystemEpisode episode) {
        if (episode.active()) {
            return episode;
        }
        return new SystemEpisode(
                episode.schemaVersion(),
                episode.id(),
                episode.graphId(),
                episode.systemAddress(),
                episode.systemName(),
                episode.startedAt(),
                null,
                episode.entrySource(),
                null,
                episode.rootOccurrenceId(),
                episode.timeline(),
                episode.occurrencesByEventType(),
                episode.occurrenceTransitions()
        );
    }

    private void completeActiveEpisode(
            EpisodeCompletionReason reason,
            Instant eventTime
    ) {
        if (activeEpisode == null) {
            return;
        }
        Instant lastOccurrenceTime =
                activeEpisode.awaitingFirstOccurrence()
                        ? activeEpisode.startedAt()
                        : activeEpisode.timeline().getLast().timestamp();
        Instant completionTime = eventTime.isBefore(lastOccurrenceTime)
                ? lastOccurrenceTime
                : eventTime;
        activeEpisode = activeEpisode.complete(completionTime, reason);
        ShipBehaviorGraph graph = requiredActiveGraph()
                .withEpisode(activeEpisode)
                .withCursor(null);
        registry.replace(graph);
        store.saveEpisode(activeEpisode);
        store.saveGraph(graph);
        advanceRevision(graph.graphId(), false);
        LOGGER.info(
                "BEHAVIOR_EPISODE_COMPLETED graphId={} episodeId={} "
                        + "reason={} occurrences={}",
                graph.graphId().canonicalValue(),
                activeEpisode.id(),
                reason,
                activeEpisode.timeline().size()
        );
        notifyListener(new SystemEpisodeCompleted(
                graph.graphId(),
                activeEpisode.id(),
                completionTime
        ));
        notifyListener(new GraphCursorChanged(
                graph.graphId(),
                Optional.empty(),
                completionTime
        ));
        notifyListener(new BehaviorGraphUpdated(
                graph.graphId(),
                completionTime
        ));
        activeEpisode = null;
        significantEventsSinceSnapshot = 0;
    }

    private void activateGraph(
            GraphId graphId,
            ContextSnapshot context,
            Instant eventTime
    ) {
        BehaviorGraphRegistry.GraphResolution resolution =
                registry.getOrCreate(
                        graphId,
                        context.shipType(),
                        context.shipName(),
                        context.loadoutHash()
                );
        revisionFor(graphId);
        if (resolution.created()) {
            store.saveGraph(resolution.graph());
            LOGGER.info(
                    "BEHAVIOR_GRAPH_CREATED graphId={}",
                    graphId.canonicalValue()
            );
            notifyListener(new BehaviorGraphCreated(graphId, eventTime));
        } else if (resolution.restored()) {
            LOGGER.info(
                    "BEHAVIOR_GRAPH_RESTORED graphId={} nodes={} edges={} "
                            + "episodes={}",
                    graphId.canonicalValue(),
                    resolution.graph().nodes().size(),
                    resolution.graph().edges().size(),
                    resolution.graph().episodes().size()
            );
        }
        if (!graphId.equals(activeGraphId)) {
            GraphId previousGraphId = activeGraphId;
            activeGraphId = graphId;
            tryRestoreActiveEpisode(resolution.graph());
            notifyListener(new ActiveGraphChanged(
                    graphId,
                    Optional.ofNullable(previousGraphId),
                    eventTime
            ));
        }
    }

    private void tryRestoreActiveEpisode(ShipBehaviorGraph graph) {
        replayProjection = null;
        List<SystemEpisode> activeEpisodes = store.listEpisodes(
                graph.graphId()
        ).stream().filter(SystemEpisode::active).toList();
        if (activeEpisodes.isEmpty()) {
            activeEpisode = null;
            if (graph.cursor() == null) {
                return;
            }
            Optional<SystemEpisode> completed =
                    store.loadEpisode(graph.cursor().episodeId())
                            .filter(episode -> !episode.active());
            if (completed.isPresent()) {
                Reconciliation reconciliation = reconcileEpisode(
                        graph,
                        completed.orElseThrow(),
                        false
                );
                registry.replace(reconciliation.graph());
                store.saveGraph(reconciliation.graph());
                LOGGER.info(
                        "BEHAVIOR_GRAPH_COMPLETED_EPISODE_RECONCILED "
                                + "graphId={} episodeId={} "
                                + "recoveredOccurrences={}",
                        graph.graphId().canonicalValue(),
                        completed.orElseThrow().id(),
                        reconciliation.recoveredOccurrences()
                );
            } else {
                ShipBehaviorGraph repaired = graph.withCursor(null);
                registry.replace(repaired);
                store.saveGraph(repaired);
                LOGGER.warn(
                        "BEHAVIOR_GRAPH_ORPHAN_CURSOR_CLEARED graphId={} "
                                + "episodeId={}",
                        graph.graphId().canonicalValue(),
                        graph.cursor().episodeId()
                );
            }
            return;
        }
        if (activeEpisodes.size() != 1) {
            throw new IllegalStateException(
                    "multiple active episodes for graph "
                            + graph.graphId().canonicalValue()
            );
        }
        activeEpisode = activeEpisodes.getFirst();
        if (graph.cursor() != null
                && !graph.cursor().episodeId().equals(activeEpisode.id())) {
            throw new IllegalStateException(
                    "active cursor and episode do not match for graph "
                            + graph.graphId().canonicalValue()
            );
        }

        Reconciliation reconciliation = reconcileEpisode(
                graph,
                activeEpisode,
                true
        );
        registry.replace(reconciliation.graph());
        if (!reconciliation.graph().equals(graph)) {
            store.saveGraph(reconciliation.graph());
            LOGGER.info(
                    "BEHAVIOR_GRAPH_ACTIVE_EPISODE_RECONCILED graphId={} "
                            + "episodeId={} recoveredOccurrences={}",
                    graph.graphId().canonicalValue(),
                    activeEpisode.id(),
                    reconciliation.recoveredOccurrences()
            );
        }
    }

    private Reconciliation reconcileEpisode(
            ShipBehaviorGraph graph,
            SystemEpisode episode,
            boolean retainCursor
    ) {
        int persistedCursorIndex = -1;
        if (graph.cursor() != null) {
            for (int index = 0;
                    index < episode.timeline().size();
                    index++) {
                EventOccurrence candidate =
                        episode.timeline().get(index);
                if (candidate.id().equals(
                        graph.cursor().occurrenceId()
                )) {
                    if (!candidate.eventType().equals(
                            graph.cursor().eventType()
                    )) {
                        throw new IllegalStateException(
                                "active cursor event type does not match episode"
                        );
                    }
                    persistedCursorIndex = index;
                    break;
                }
            }
            if (persistedCursorIndex < 0) {
                throw new IllegalStateException(
                        "active cursor occurrence is missing from episode"
                );
            }
        }

        ShipBehaviorGraph reconciled = graph;
        for (int index = persistedCursorIndex + 1;
                index < episode.timeline().size();
                index++) {
            EventOccurrence occurrence = episode.timeline().get(index);
            reconciled = reconciled.recordOccurrence(occurrence);
            if (index > 0) {
                EventOccurrence previous =
                        episode.timeline().get(index - 1);
                OccurrenceTransition transition =
                        episode.occurrenceTransitions().stream()
                                .filter(candidate ->
                                        candidate.fromOccurrenceId()
                                                .equals(previous.id())
                                                && candidate.toOccurrenceId()
                                                .equals(occurrence.id()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException(
                                        "active episode transition is missing"
                                ));
                reconciled = reconciled.recordTransition(
                        transition,
                        halfLife
                );
            }
            reconciled = reconciled.withCursor(cursorFor(occurrence));
        }
        reconciled = reconciled.withEpisode(episode);
        if (!retainCursor) {
            reconciled = reconciled.withCursor(null);
        }
        return new Reconciliation(
                reconciled,
                episode.timeline().size() - persistedCursorIndex - 1
        );
    }

    private void persistMetadataIfNeeded(JournalEventObservation payload) {
        if (!(payload instanceof Loadout) || activeGraphId == null) {
            return;
        }
        ShipBehaviorGraph graph = requiredActiveGraph();
        if (activeEpisode != null) {
            graph = graph.withEpisode(activeEpisode);
            registry.replace(graph);
            persistActive(graph);
        } else {
            store.saveGraph(graph);
        }
    }

    private void persistActive(ShipBehaviorGraph graph) {
        if (activeEpisode != null) {
            store.saveEpisode(activeEpisode);
        }
        store.saveGraph(graph);
    }

    private void saveAllLoadedGraphs() {
        for (ShipBehaviorGraph graph : registry.loadedGraphs()) {
            store.saveGraph(graph);
        }
    }

    private BehaviorGraphApplyResult applyResult(
            PublishedObservation<?> observation,
            BehaviorGraphApplyStatus preferredStatus,
            GraphCommitMarker before
    ) {
        GraphCommitMarker after = graphCommitMarker();
        BehaviorGraphChangeSet changes =
                graphChanges(before, after);
        BehaviorGraphApplyStatus status = changes.changed()
                ? BehaviorGraphApplyStatus.APPLIED
                : preferredStatus == BehaviorGraphApplyStatus.APPLIED
                        ? BehaviorGraphApplyStatus.NOT_APPLICABLE
                        : preferredStatus;
        if (status == BehaviorGraphApplyStatus.NO_GRAPH_ID) {
            return BehaviorGraphApplyResult.noGraphId(
                    observation.busSequence()
            );
        }
        Optional<GraphId> graphId = Optional.ofNullable(activeGraphId);
        SystemEpisode currentEpisode = currentEpisode();
        Optional<SystemEpisodeId> episodeId =
                currentEpisode == null
                        ? Optional.empty()
                        : Optional.of(currentEpisode.id());
        Optional<GraphCursor> currentCursor =
                graphId.flatMap(this::cursor);
        if (graphId.isEmpty()) {
            return new BehaviorGraphApplyResult(
                    observation.busSequence(),
                    status,
                    changes,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty()
            );
        }
        GraphRevision revision = graphRevisions.getOrDefault(
                graphId.orElseThrow(),
                GraphRevision.INITIAL
        );
        return new BehaviorGraphApplyResult(
                observation.busSequence(),
                status,
                changes,
                graphId,
                episodeId,
                currentCursor,
                OptionalLong.of(revision.graphVersion()),
                OptionalLong.of(revision.topologyVersion())
        );
    }

    private GraphCommitMarker graphCommitMarker() {
        if (activeGraphId == null) {
            return GraphCommitMarker.NONE;
        }
        SystemEpisode episode = currentEpisode();
        return new GraphCommitMarker(
                activeGraphId,
                graphRevisions.getOrDefault(
                        activeGraphId,
                        GraphRevision.INITIAL
                ),
                episode == null ? null : episode.id(),
                cursor(activeGraphId).orElse(null),
                episode == null ? 0L : episode.timeline().size()
        );
    }

    private static BehaviorGraphChangeSet graphChanges(
            GraphCommitMarker before,
            GraphCommitMarker after
    ) {
        boolean ownerChanged = !Objects.equals(
                before.graphId(),
                after.graphId()
        );
        boolean episodeChanged = !Objects.equals(
                before.activeEpisodeId(),
                after.activeEpisodeId()
        );
        boolean sameEpisode = Objects.equals(
                before.activeEpisodeId(),
                after.activeEpisodeId()
        );
        boolean occurrenceAdded =
                after.activeEpisodeId() != null
                        && (sameEpisode
                        ? after.activeOccurrenceCount()
                        > before.activeOccurrenceCount()
                        : after.activeOccurrenceCount() > 0);
        boolean cursorChanged = !Objects.equals(
                before.cursor(),
                after.cursor()
        );
        boolean sameOwner = Objects.equals(
                before.graphId(),
                after.graphId()
        );
        boolean graphRevisionChanged = sameOwner
                && before.revision().graphVersion()
                != after.revision().graphVersion();
        boolean topologyRevisionChanged = sameOwner
                && before.revision().topologyVersion()
                != after.revision().topologyVersion();
        return new BehaviorGraphChangeSet(
                ownerChanged,
                episodeChanged,
                occurrenceAdded,
                cursorChanged,
                graphRevisionChanged,
                topologyRevisionChanged
        );
    }

    private GraphRevision revisionFor(GraphId graphId) {
        return graphRevisions.computeIfAbsent(
                graphId,
                ignored -> GraphRevision.INITIAL
        );
    }

    private void advanceRevision(
            GraphId graphId,
            boolean topologyChanged
    ) {
        GraphRevision current = revisionFor(graphId);
        graphRevisions.put(
                graphId,
                new GraphRevision(
                        Math.incrementExact(current.graphVersion()),
                        topologyChanged
                                ? Math.incrementExact(
                                        current.topologyVersion()
                                )
                                : current.topologyVersion()
                )
        );
    }

    private static String shipDisplayName(ShipBehaviorGraph graph) {
        if (graph.shipName() != null && !graph.shipName().isBlank()) {
            return graph.shipName();
        }
        if (graph.shipType() != null && !graph.shipType().isBlank()) {
            return graph.shipType();
        }
        return graph.graphId().canonicalValue();
    }

    private static Map<NormalizedEventType, Long>
            activeEpisodeOccurrenceCounts(
                    Optional<SystemEpisode> episode
            ) {
        if (episode.isEmpty()) {
            return Map.of();
        }
        Map<NormalizedEventType, Long> counts = new TreeMap<>();
        episode.orElseThrow().occurrencesByEventType().forEach(
                (eventType, occurrenceIds) -> counts.put(
                        eventType,
                        (long) occurrenceIds.size()
                )
        );
        return Map.copyOf(counts);
    }

    private List<SituationNextEventPrediction> situationPredictions(
            GraphId graphId,
            GraphCursor cursor,
            CurrentGameStateSnapshot currentState
    ) {
        ShipBehaviorGraph graph = registry.find(graphId)
                .orElseThrow(() -> inconsistent(
                        "active graph is missing from registry"
                ));
        List<NextEventPrediction> predictions =
                probabilityCalculator.predict(
                        graph,
                        cursor,
                        contextAdapter.toContextSnapshot(currentState),
                        latestEventTime,
                        Math.max(1, graph.edges().size())
                );
        for (NextEventPrediction prediction : predictions) {
            if (!prediction.graphId().equals(graphId)
                    || !prediction.episodeId().equals(cursor.episodeId())
                    || !prediction.currentOccurrenceId().equals(
                            cursor.occurrenceId()
                    )
                    || !prediction.currentEventType().equals(
                            cursor.eventType()
                    )) {
                throw inconsistent(
                        "prediction does not belong to captured cursor"
                );
            }
        }
        // Straight propagation. Nothing is recomputed, reweighted or renamed:
        // the snapshot carries exactly what the calculator established.
        return predictions.stream()
                .map(prediction -> new SituationNextEventPrediction(
                        prediction.currentEventType(),
                        prediction.predictedEventType(),
                        prediction.probability(),
                        prediction.basis(),
                        prediction.globalProbability(),
                        prediction.rawTransitionCount(),
                        prediction.contextRawTransitionCount(),
                        prediction.contextSupport(),
                        prediction.contextKey(),
                        prediction.effectiveWeight()
                ))
                .toList();
    }

    private GraphId requireExpectedGraph(
            BehaviorGraphApplyResult applyResult,
            CurrentGameStateSnapshot currentState
    ) {
        GraphId expected = applyResult.activeGraphId()
                .orElseThrow(() -> inconsistent(
                        "apply result is missing active graph metadata"
                ));
        if (!expected.equals(activeGraphId)) {
            throw inconsistent(
                    "active graph mismatch expected="
                            + expected.canonicalValue()
                            + " actual="
                            + activeGraphId.canonicalValue()
            );
        }
        Optional<GraphId> stateGraphId =
                contextAdapter.graphId(currentState);
        if (stateGraphId.isEmpty()
                || !stateGraphId.orElseThrow().equals(expected)) {
            throw inconsistent(
                    "canonical graph mismatch expected="
                            + expected.canonicalValue()
                            + " actual="
                            + stateGraphId
                            .map(GraphId::canonicalValue)
                            .orElse("<none>")
            );
        }
        if (!contextAdapter.toContextSnapshot(currentState)
                .equals(currentContext)) {
            throw inconsistent(
                    "canonical state does not match committed graph context"
            );
        }
        return expected;
    }

    private static void requireExpectedRevision(
            BehaviorGraphApplyResult applyResult,
            GraphRevision actual
    ) {
        if (applyResult.graphVersion().isEmpty()
                || applyResult.topologyVersion().isEmpty()
                || applyResult.graphVersion().orElseThrow()
                != actual.graphVersion()
                || applyResult.topologyVersion().orElseThrow()
                != actual.topologyVersion()) {
            throw inconsistent(
                    "graph revision mismatch expected=("
                            + optionalLongValue(
                                    applyResult.graphVersion()
                            )
                            + ','
                            + optionalLongValue(
                                    applyResult.topologyVersion()
                            )
                            + ") actual=("
                            + actual.graphVersion()
                            + ','
                            + actual.topologyVersion()
                            + ')'
            );
        }
    }

    private static void requireExpectedEpisodeAndCursor(
            BehaviorGraphApplyResult applyResult,
            SystemEpisode episode,
            GraphCursor cursor
    ) {
        if (!episode.active()
                || !applyResult.activeEpisodeId()
                .filter(episode.id()::equals)
                .isPresent()
                || !applyResult.cursor().filter(cursor::equals).isPresent()
                || !cursor.graphId().equals(episode.graphId())
                || !cursor.episodeId().equals(episode.id())) {
            throw inconsistent(
                    "episode/cursor mismatch expectedEpisode="
                            + applyResult.activeEpisodeId()
                            .map(SystemEpisodeId::value)
                            .orElse("<none>")
                            + " actualEpisode="
                            + episode.id().value()
                            + " expectedCursor="
                            + applyResult.cursor()
                            .map(value -> value.occurrenceId().value())
                            .orElse("<none>")
                            + " actualCursor="
                            + cursor.occurrenceId().value()
            );
        }
    }

    private static EventOccurrence requireCurrentOccurrence(
            SystemEpisode episode,
            GraphCursor cursor
    ) {
        List<EventOccurrence> timeline = episode.timeline();
        EventOccurrence current = timeline.stream()
                .filter(occurrence -> occurrence.id().equals(
                        cursor.occurrenceId()
                ))
                .findFirst()
                .orElseThrow(() -> inconsistent(
                        "cursor occurrence is missing from active episode"
                ));
        if (!timeline.getLast().equals(current)
                || !current.eventType().equals(cursor.eventType())
                || !current.timestamp().equals(cursor.updatedAt())) {
            throw inconsistent(
                    "cursor must identify the final active occurrence"
            );
        }
        return current;
    }

    private static void requireEmptyGraphMetadata(
            BehaviorGraphApplyResult applyResult
    ) {
        if (applyResult.activeGraphId().isPresent()
                || applyResult.activeEpisodeId().isPresent()
                || applyResult.cursor().isPresent()
                || applyResult.graphVersion().isPresent()
                || applyResult.topologyVersion().isPresent()) {
            throw inconsistent(
                    "apply result claims graph metadata without active graph"
            );
        }
    }

    private static BehaviorSituationInconsistencyException inconsistent(
            String message
    ) {
        return new BehaviorSituationInconsistencyException(message);
    }

    private static String optionalLongValue(OptionalLong value) {
        return value.isPresent()
                ? Long.toString(value.orElseThrow())
                : "<none>";
    }

    private static GraphCursor visualizationCursor(
            GraphCursor cursor,
            GraphId graphId,
            Optional<SystemEpisode> episode
    ) {
        if (cursor == null) {
            return null;
        }
        if (episode.isEmpty()
                || !cursor.episodeId().equals(episode.orElseThrow().id())) {
            LOGGER.warn(
                    "BEHAVIOR_GRAPH_CURSOR_WITHOUT_ACTIVE_EPISODE graphId={} "
                            + "episodeId={}",
                    graphId.canonicalValue(),
                    cursor.episodeId()
            );
            return null;
        }
        Optional<EventOccurrence> occurrence =
                episode.orElseThrow().timeline().stream()
                        .filter(candidate -> candidate.id().equals(
                                cursor.occurrenceId()
                        ))
                        .findFirst();
        if (occurrence.isEmpty()
                || !occurrence.orElseThrow().eventType().equals(
                        cursor.eventType()
                )) {
            LOGGER.warn(
                    "BEHAVIOR_GRAPH_CURSOR_OCCURRENCE_MISSING graphId={} "
                            + "episodeId={} occurrenceId={}",
                    graphId.canonicalValue(),
                    cursor.episodeId(),
                    cursor.occurrenceId()
            );
            return null;
        }
        return cursor;
    }

    private static EventOccurrenceSummary occurrenceSummary(
            EventOccurrence occurrence
    ) {
        return new EventOccurrenceSummary(
                occurrence.id(),
                occurrence.timestamp(),
                occurrence.episodeSequence(),
                occurrence.sourceSequence(),
                occurrence.originalEventName()
        );
    }

    private static EventOccurrenceDetailsSnapshot occurrenceDetails(
            EventOccurrence occurrence
    ) {
        return new EventOccurrenceDetailsSnapshot(
                occurrence.graphId(),
                occurrence.episodeId(),
                occurrence.id(),
                occurrence.eventType(),
                occurrence.originalEventName(),
                occurrence.timestamp(),
                occurrence.episodeSequence(),
                occurrence.sourceSequence(),
                occurrence.sourceId(),
                occurrence.attributes(),
                occurrence.context()
        );
    }

    private boolean occurrenceAlreadyRecorded(
            EventOccurrenceId occurrenceId,
            GraphId graphId,
            SourceReference source,
            NormalizedEventType eventType,
            String originalEventName
    ) {
        if (occurrencesSeenInProcess.contains(occurrenceId)) {
            return true;
        }
        Optional<EventOccurrence> existing =
                store.findOccurrence(occurrenceId);
        if (existing.isEmpty()) {
            return false;
        }
        EventOccurrence stored = existing.orElseThrow();
        if (!stored.graphId().equals(graphId)
                || !stored.sourceId().equals(source.sourceId())
                || stored.sourceSequence() != source.sourceSequence()
                || !stored.eventType().equals(eventType)
                || !stored.originalEventName().equals(originalEventName)) {
            throw new IllegalStateException(
                    "behavior occurrence identity collision: " + occurrenceId
            );
        }
        return true;
    }

    private ShipBehaviorGraph requiredActiveGraph() {
        if (activeGraphId == null) {
            throw new IllegalStateException("no active behavior graph");
        }
        return registry.find(activeGraphId)
                .orElseThrow(() -> new IllegalStateException(
                        "active behavior graph is missing"
                ));
    }

    private static GraphCursor cursorFor(EventOccurrence occurrence) {
        return new GraphCursor(
                occurrence.graphId(),
                occurrence.episodeId(),
                occurrence.id(),
                occurrence.eventType(),
                occurrence.timestamp()
        );
    }

    private static String systemName(ContextSnapshot context) {
        if (context.systemName() != null && !context.systemName().isBlank()) {
            return context.systemName();
        }
        return "System " + context.systemAddress();
    }

    private static SourceReference journalSourceReference(
            PublishedObservation<? extends JournalEventObservation> observation
    ) {
        if (!(observation.sourcePosition()
                instanceof JournalSourcePosition journalPosition)) {
            throw new IllegalArgumentException(
                    "behavior graph requires JournalSourcePosition"
            );
        }
        // The byte offset is a stable monotonic sequence within sourceId
        // (journal basename) and is identical for repeated replay of that file.
        return new SourceReference(
                journalPosition.journalBasename(),
                journalPosition.zeroBasedSourceByteOffset()
        );
    }

    private static SourceReference statusSourceReference(
            PublishedObservation<StatusSnapshotObservation> observation
    ) {
        if (!(observation.sourcePosition()
                instanceof StatusSourcePosition statusPosition)) {
            throw new IllegalArgumentException(
                    "behavior graph requires StatusSourcePosition"
            );
        }
        return new SourceReference(
                statusPosition.statusBasename(),
                statusPosition.snapshotSequence()
        );
    }

    private static Instant deterministicEventTime(
            PublishedObservation<? extends JournalEventObservation> observation
    ) {
        return observation.sourceTime()
                .or(() -> observation.payload()
                        .raw()
                        .optionalJournalTimestamp())
                .orElse(Instant.EPOCH);
    }

    private void requireIncreasingBusSequence(
            PublishedObservation<?> observation
    ) {
        if (observation.busSequence() <= lastBusSequence) {
            LOGGER.error(
                    "BEHAVIOR_GRAPH_ORDER_VIOLATION observationId={} "
                            + "busSequence={} previousBusSequence={}",
                    observation.observationId(),
                    observation.busSequence(),
                    lastBusSequence
            );
            throw new IllegalStateException(
                    "behavior graph observations are out of bus order"
            );
        }
        lastBusSequence = observation.busSequence();
    }

    private static void diagnoseMissingIdentity(
            PublishedObservation<? extends JournalEventObservation> observation,
            String operation
    ) {
        LOGGER.warn(
                "BEHAVIOR_GRAPH_IDENTITY_MISSING operation={} "
                        + "observationId={} eventType={}",
                operation,
                observation.observationId(),
                observation.payload().getClass().getSimpleName()
        );
    }

    private void notifyListener(BehaviorGraphEvent event) {
        try {
            eventBus.onBehaviorGraphEvent(event);
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "BEHAVIOR_GRAPH_LISTENER_FAILED eventType={} graphId={} "
                            + "category={}",
                    event.getClass().getSimpleName(),
                    event.graphId().canonicalValue(),
                    failure.getClass().getSimpleName()
            );
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("BehaviorGraphService is closed");
        }
    }

    private record GraphRevision(
            long graphVersion,
            long topologyVersion
    ) {

        private static final GraphRevision INITIAL =
                new GraphRevision(0, 0);

        private GraphRevision {
            if (graphVersion < 0 || topologyVersion < 0) {
                throw new IllegalArgumentException(
                        "graph revisions must be nonnegative"
                );
            }
        }
    }

    private record GraphCommitMarker(
            GraphId graphId,
            GraphRevision revision,
            SystemEpisodeId activeEpisodeId,
            GraphCursor cursor,
            long activeOccurrenceCount
    ) {

        private static final GraphCommitMarker NONE =
                new GraphCommitMarker(
                        null,
                        GraphRevision.INITIAL,
                        null,
                        null,
                        0L
                );

        private GraphCommitMarker {
            Objects.requireNonNull(revision, "revision");
            if (activeOccurrenceCount < 0) {
                throw new IllegalArgumentException(
                        "activeOccurrenceCount must be nonnegative"
                );
            }
        }
    }

    private record SourceReference(String sourceId, long sourceSequence) {

        private SourceReference {
            Objects.requireNonNull(sourceId, "sourceId");
            if (sourceSequence < 0) {
                throw new IllegalArgumentException(
                        "sourceSequence must be nonnegative"
                );
            }
        }
    }

    private record Reconciliation(
            ShipBehaviorGraph graph,
            int recoveredOccurrences
    ) {

        private Reconciliation {
            Objects.requireNonNull(graph, "graph");
            if (recoveredOccurrences < 0) {
                throw new IllegalArgumentException(
                        "recoveredOccurrences must be nonnegative"
                );
            }
        }
    }

    private record ReplayEpisodeProjection(
            SystemEpisode sourceEpisode,
            SystemEpisode visibleEpisode,
            GraphCursor cursor
    ) {

        private ReplayEpisodeProjection {
            Objects.requireNonNull(sourceEpisode, "sourceEpisode");
            Objects.requireNonNull(visibleEpisode, "visibleEpisode");
            // Null exactly while a restored episode has exposed nothing yet.
            if ((cursor == null) != visibleEpisode.awaitingFirstOccurrence()) {
                throw new IllegalArgumentException(
                        "a replay cursor is absent only before the first "
                                + "exposed occurrence"
                );
            }
            if (!sourceEpisode.id().equals(visibleEpisode.id())
                    || !sourceEpisode.graphId().equals(
                            visibleEpisode.graphId()
                    )
                    || cursor != null
                    && (!cursor.episodeId().equals(visibleEpisode.id())
                    || !cursor.graphId().equals(visibleEpisode.graphId()))) {
                throw new IllegalArgumentException(
                        "replay projection identities must match"
                );
            }
            if (!visibleEpisode.active()) {
                throw new IllegalArgumentException(
                        "visible replay episode must remain active"
                );
            }
        }
    }

    private record PendingLocation(
            PublishedObservation<? extends JournalEventObservation>
                    observation,
            SourceReference source,
            Instant eventTime
    ) {

        private PendingLocation {
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(eventTime, "eventTime");
        }
    }
}
