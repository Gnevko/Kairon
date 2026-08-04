package kairon.behavior.model;

import kairon.behavior.normalize.NormalizedEventType;

import java.time.Instant;
import java.util.Objects;

public record NextEventPrediction(
        GraphId graphId,
        SystemEpisodeId episodeId,
        EventOccurrenceId currentOccurrenceId,
        NormalizedEventType currentEventType,
        NormalizedEventType predictedEventType,
        double probability,
        double globalProbability,
        double effectiveWeight,
        long rawTransitionCount,
        long contextRawTransitionCount,
        ContextKey contextKey,
        double contextSupport,
        PredictionBasis basis,
        Instant lastSeenAt
) {

    public NextEventPrediction {
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(episodeId, "episodeId");
        Objects.requireNonNull(currentOccurrenceId, "currentOccurrenceId");
        Objects.requireNonNull(currentEventType, "currentEventType");
        Objects.requireNonNull(predictedEventType, "predictedEventType");
        requireProbability(probability, "probability");
        requireProbability(globalProbability, "globalProbability");
        if (!Double.isFinite(effectiveWeight) || effectiveWeight < 0.0) {
            throw new IllegalArgumentException(
                    "effectiveWeight must be finite and nonnegative"
            );
        }
        if (rawTransitionCount < 1) {
            throw new IllegalArgumentException(
                    "rawTransitionCount must be positive"
            );
        }
        // The all-time count inside one context bucket is a subset of the
        // all-time count across every bucket.
        if (contextRawTransitionCount < 0
                || contextRawTransitionCount > rawTransitionCount) {
            throw new IllegalArgumentException(
                    "contextRawTransitionCount must be within "
                            + "rawTransitionCount"
            );
        }
        Objects.requireNonNull(contextKey, "contextKey");
        if (!Double.isFinite(contextSupport) || contextSupport < 0.0) {
            throw new IllegalArgumentException(
                    "contextSupport must be finite and nonnegative"
            );
        }
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    }

    private static void requireProbability(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be between zero and one"
            );
        }
    }
}
