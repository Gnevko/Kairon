package kairon.behavior.snapshot;

import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.graph.BehaviorGraphApplyStatus;
import kairon.behavior.model.GraphId;
import kairon.behavior.normalize.NormalizedEventType;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable graph situation captured after one terminal graph apply.
 */
public record BehaviorSituationSnapshot(
        long busSequence,
        BehaviorSituationCaptureStatus captureStatus,
        BehaviorGraphApplyResult applyResult,
        Optional<ActiveEpisodeSituation> activeEpisode,
        List<SituationNextEventPrediction> likelyNext
) {

    private static final double PROBABILITY_TOLERANCE = 1.0e-9;

    public BehaviorSituationSnapshot {
        if (busSequence < 1) {
            throw new IllegalArgumentException(
                    "busSequence must be positive"
            );
        }
        captureStatus = Objects.requireNonNull(
                captureStatus,
                "captureStatus"
        );
        applyResult = Objects.requireNonNull(applyResult, "applyResult");
        activeEpisode = Objects.requireNonNull(
                activeEpisode,
                "activeEpisode"
        );
        likelyNext = List.copyOf(
                Objects.requireNonNull(likelyNext, "likelyNext")
        );
        if (busSequence != applyResult.busSequence()) {
            throw new IllegalArgumentException(
                    "apply result does not belong to situation snapshot"
            );
        }
        requireStatusCompatibility(
                captureStatus,
                applyResult.status(),
                activeEpisode
        );
        if (activeEpisode.isEmpty() && !likelyNext.isEmpty()) {
            throw new IllegalArgumentException(
                    "predictions require an active episode situation"
            );
        }
        if (activeEpisode.isPresent()) {
            ActiveEpisodeSituation episode = activeEpisode.orElseThrow();
            requireApplyMetadata(episode, applyResult);
            requirePredictions(episode, likelyNext);
        }
    }

    public static BehaviorSituationSnapshot available(
            BehaviorGraphApplyResult applyResult,
            ActiveEpisodeSituation activeEpisode,
            List<SituationNextEventPrediction> likelyNext
    ) {
        Objects.requireNonNull(applyResult, "applyResult");
        BehaviorSituationCaptureStatus status =
                applyResult.status() == BehaviorGraphApplyStatus.APPLIED
                        ? BehaviorSituationCaptureStatus.AVAILABLE
                        : BehaviorSituationCaptureStatus.UNCHANGED;
        return new BehaviorSituationSnapshot(
                applyResult.busSequence(),
                status,
                applyResult,
                Optional.of(activeEpisode),
                likelyNext
        );
    }

    public static BehaviorSituationSnapshot unavailable(
            BehaviorGraphApplyResult applyResult,
            BehaviorSituationCaptureStatus captureStatus
    ) {
        Objects.requireNonNull(applyResult, "applyResult");
        return new BehaviorSituationSnapshot(
                applyResult.busSequence(),
                captureStatus,
                applyResult,
                Optional.empty(),
                List.of()
        );
    }

    public Optional<GraphId> activeGraphId() {
        return applyResult.activeGraphId();
    }

    private static void requireStatusCompatibility(
            BehaviorSituationCaptureStatus captureStatus,
            BehaviorGraphApplyStatus applyStatus,
            Optional<ActiveEpisodeSituation> activeEpisode
    ) {
        boolean available = captureStatus
                == BehaviorSituationCaptureStatus.AVAILABLE
                || captureStatus
                == BehaviorSituationCaptureStatus.UNCHANGED;
        if (available != activeEpisode.isPresent()) {
            throw new IllegalArgumentException(
                    "capture availability does not match active episode"
            );
        }
        boolean compatible = switch (captureStatus) {
            case AVAILABLE -> applyStatus == BehaviorGraphApplyStatus.APPLIED;
            case UNCHANGED ->
                    applyStatus == BehaviorGraphApplyStatus.NOT_APPLICABLE;
            case GRAPH_DISABLED ->
                    applyStatus == BehaviorGraphApplyStatus.DISABLED;
            case NO_GRAPH_ID ->
                    applyStatus == BehaviorGraphApplyStatus.NO_GRAPH_ID;
            case GRAPH_APPLY_FAILED ->
                    applyStatus == BehaviorGraphApplyStatus.FAILED;
            case NO_ACTIVE_GRAPH ->
                    applyStatus == BehaviorGraphApplyStatus.NOT_APPLICABLE;
            case NO_ACTIVE_EPISODE ->
                    applyStatus == BehaviorGraphApplyStatus.APPLIED
                            || applyStatus
                            == BehaviorGraphApplyStatus.NOT_APPLICABLE;
            case SNAPSHOT_FAILED, INCONSISTENT ->
                    applyStatus != BehaviorGraphApplyStatus.DISABLED
                            && applyStatus != BehaviorGraphApplyStatus.FAILED;
        };
        if (!compatible) {
            throw new IllegalArgumentException(
                    "capture status is incompatible with graph apply status"
            );
        }
    }

    private static void requireApplyMetadata(
            ActiveEpisodeSituation episode,
            BehaviorGraphApplyResult applyResult
    ) {
        if (!applyResult.activeGraphId()
                .filter(episode.graphId()::equals)
                .isPresent()
                || !applyResult.activeEpisodeId()
                .filter(episode.episodeId()::equals)
                .isPresent()
                || !applyResult.cursor()
                .filter(episode.cursor()::equals)
                .isPresent()
                || applyResult.graphVersion().isEmpty()
                || applyResult.graphVersion().orElseThrow()
                != episode.graphVersion()
                || applyResult.topologyVersion().isEmpty()
                || applyResult.topologyVersion().orElseThrow()
                != episode.topologyVersion()) {
            throw new IllegalArgumentException(
                    "active episode does not match graph apply metadata"
            );
        }
    }

    private static void requirePredictions(
            ActiveEpisodeSituation episode,
            List<SituationNextEventPrediction> predictions
    ) {
        Comparator<SituationNextEventPrediction> order =
                Comparator.comparingDouble(
                                SituationNextEventPrediction::probability
                        )
                        .reversed()
                        .thenComparing(
                                SituationNextEventPrediction
                                        ::predictedEventType
                        );
        double total = 0.0;
        for (int index = 0; index < predictions.size(); index++) {
            SituationNextEventPrediction prediction =
                    Objects.requireNonNull(
                            predictions.get(index),
                            "prediction"
                    );
            NormalizedEventType currentType =
                    episode.cursor().eventType();
            if (!prediction.sourceEventType().equals(currentType)) {
                throw new IllegalArgumentException(
                        "prediction source must match current event type"
                );
            }
            if (index > 0
                    && order.compare(
                            predictions.get(index - 1),
                            prediction
                    ) > 0) {
                throw new IllegalArgumentException(
                        "predictions must use deterministic probability order"
                );
            }
            total += prediction.probability();
        }
        if (!predictions.isEmpty()
                && Math.abs(total - 1.0) > PROBABILITY_TOLERANCE) {
            throw new IllegalArgumentException(
                    "prediction probabilities must sum to one"
            );
        }
    }
}
