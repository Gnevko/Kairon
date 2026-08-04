package kairon.behavior.graph;

import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.NextEventPrediction;
import kairon.behavior.model.ShipBehaviorGraphSummary;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.model.SystemEpisodeSummary;
import kairon.behavior.model.TransitionEdgeView;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.behavior.snapshot.BehaviorSituationSnapshotProvider;
import kairon.observation.PublishedObservation;
import kairon.state.CurrentGameStateSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only application boundary for querying exact episode paths and the
 * aggregate per-ship behavior graph.
 */
public final class BehaviorGraphQueryService
        implements BehaviorGraphVisualizationQuery,
        BehaviorSituationSnapshotProvider {

    private final BehaviorGraphService graphService;

    public BehaviorGraphQueryService(BehaviorGraphService graphService) {
        this.graphService = Objects.requireNonNull(
                graphService,
                "graphService"
        );
    }

    @Override
    public Optional<GraphId> getActiveGraphId() {
        return graphService.currentGraphId();
    }

    @Override
    public Optional<BehaviorGraphVisualizationSnapshot>
            getVisualizationSnapshot(
                    GraphId graphId,
                    Instant evaluationTime
            ) {
        return graphService.visualizationSnapshot(
                requireGraphId(graphId),
                Objects.requireNonNull(evaluationTime, "evaluationTime")
        );
    }

    @Override
    public ActiveEpisodeNodeOccurrencesSnapshot
            getActiveEpisodeOccurrences(
                    GraphId graphId,
                    NormalizedEventType eventType
            ) {
        return graphService.activeEpisodeOccurrences(
                requireGraphId(graphId),
                Objects.requireNonNull(eventType, "eventType")
        );
    }

    @Override
    public Optional<EventOccurrenceDetailsSnapshot>
            getActiveEpisodeOccurrenceDetails(
                    GraphId graphId,
                    SystemEpisodeId episodeId,
                    EventOccurrenceId occurrenceId
            ) {
        return graphService.activeEpisodeOccurrenceDetails(
                requireGraphId(graphId),
                Objects.requireNonNull(episodeId, "episodeId"),
                Objects.requireNonNull(occurrenceId, "occurrenceId")
        );
    }

    public ShipBehaviorGraphSummary getGraphSummary(GraphId graphId) {
        return graphService.summary(requireGraphId(graphId));
    }

    public Optional<GraphCursor> getCurrentCursor(GraphId graphId) {
        return graphService.cursor(requireGraphId(graphId));
    }

    public Optional<SystemEpisode> getActiveEpisode(GraphId graphId) {
        return graphService.activeEpisode(requireGraphId(graphId));
    }

    public List<SystemEpisodeSummary> listEpisodes(GraphId graphId) {
        return graphService.episodes(requireGraphId(graphId)).stream()
                .map(SystemEpisodeSummary::from)
                .sorted()
                .toList();
    }

    public List<EventOccurrence> getOccurrences(
            GraphId graphId,
            SystemEpisodeId episodeId,
            NormalizedEventType eventType
    ) {
        requireGraphId(graphId);
        Objects.requireNonNull(episodeId, "episodeId");
        Objects.requireNonNull(eventType, "eventType");
        return graphService.episodes(graphId).stream()
                .filter(episode -> episode.id().equals(episodeId))
                .filter(episode -> episode.graphId().equals(graphId))
                .findFirst()
                .map(episode -> occurrencesOfType(episode, eventType))
                .orElseGet(List::of);
    }

    public List<EventOccurrence> getAllOccurrences(
            GraphId graphId,
            NormalizedEventType eventType,
            int limit
    ) {
        requireGraphId(graphId);
        Objects.requireNonNull(eventType, "eventType");
        requirePositiveLimit(limit);
        return graphService.episodes(graphId).stream()
                .flatMap(episode -> occurrencesOfType(
                        episode,
                        eventType
                ).stream())
                .sorted(EventOccurrence.CHRONOLOGICAL_ORDER)
                .limit(limit)
                .toList();
    }

    public List<TransitionEdgeView> getOutgoingEdges(
            GraphId graphId,
            NormalizedEventType eventType,
            Instant evaluationTime
    ) {
        return graphService.outgoingEdges(
                requireGraphId(graphId),
                Objects.requireNonNull(eventType, "eventType"),
                Objects.requireNonNull(evaluationTime, "evaluationTime")
        );
    }

    public List<NextEventPrediction> predictNext(
            GraphId graphId,
            ContextSnapshot currentContext,
            Instant evaluationTime,
            int limit
    ) {
        requirePositiveLimit(limit);
        requireGraphId(graphId);
        Objects.requireNonNull(currentContext, "currentContext");
        if (currentContext.commanderFid() != null
                && !currentContext.commanderFid().equals(
                        graphId.commanderFid()
                )
                || currentContext.shipId() != null
                && currentContext.shipId() != graphId.shipId()) {
            throw new IllegalArgumentException(
                    "currentContext belongs to another behavior graph"
            );
        }
        return graphService.predictNext(
                graphId,
                currentContext,
                Objects.requireNonNull(evaluationTime, "evaluationTime"),
                limit
        );
    }

    @Override
    public BehaviorSituationSnapshot capture(
            PublishedObservation<?> trigger,
            CurrentGameStateSnapshot currentState,
            BehaviorGraphApplyResult applyResult
    ) {
        return graphService.captureSituation(
                Objects.requireNonNull(trigger, "trigger"),
                Objects.requireNonNull(currentState, "currentState"),
                Objects.requireNonNull(applyResult, "applyResult")
        );
    }

    private static List<EventOccurrence> occurrencesOfType(
            SystemEpisode episode,
            NormalizedEventType eventType
    ) {
        return episode.timeline().stream()
                .filter(occurrence -> occurrence.eventType().equals(eventType))
                .sorted(EventOccurrence.CHRONOLOGICAL_ORDER)
                .toList();
    }

    private static GraphId requireGraphId(GraphId graphId) {
        return Objects.requireNonNull(graphId, "graphId");
    }

    private static void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
