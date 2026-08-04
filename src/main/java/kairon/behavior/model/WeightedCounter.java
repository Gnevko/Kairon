package kairon.behavior.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Incremental all-time count plus an exponentially decayed current value.
 */
public record WeightedCounter(
        long rawCount,
        double decayedValue,
        Instant lastUpdatedAt,
        Instant firstSeenAt,
        Instant lastSeenAt
) {

    public WeightedCounter {
        if (rawCount < 0) {
            throw new IllegalArgumentException("rawCount must be nonnegative");
        }
        if (!Double.isFinite(decayedValue) || decayedValue < 0.0) {
            throw new IllegalArgumentException(
                    "decayedValue must be finite and nonnegative"
            );
        }
        if (rawCount == 0) {
            if (decayedValue != 0.0
                    || lastUpdatedAt != null
                    || firstSeenAt != null
                    || lastSeenAt != null) {
                throw new IllegalArgumentException(
                        "empty counter must not contain timestamps or weight"
                );
            }
        } else {
            Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt");
            Objects.requireNonNull(firstSeenAt, "firstSeenAt");
            Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        }
    }

    public static WeightedCounter empty() {
        return new WeightedCounter(0, 0.0, null, null, null);
    }

    public WeightedCounter record(Instant eventTime, Duration halfLife) {
        Objects.requireNonNull(eventTime, "eventTime");
        requirePositive(halfLife);
        if (rawCount == 0) {
            return new WeightedCounter(1, 1.0, eventTime, eventTime, eventTime);
        }

        Instant effectiveTime = eventTime.isBefore(lastUpdatedAt)
                ? lastUpdatedAt
                : eventTime;
        return new WeightedCounter(
                rawCount + 1,
                valueAt(effectiveTime, halfLife) + 1.0,
                effectiveTime,
                firstSeenAt.isAfter(eventTime) ? eventTime : firstSeenAt,
                lastSeenAt.isBefore(eventTime) ? eventTime : lastSeenAt
        );
    }

    public double valueAt(Instant evaluationTime, Duration halfLife) {
        Objects.requireNonNull(evaluationTime, "evaluationTime");
        requirePositive(halfLife);
        if (rawCount == 0) {
            return 0.0;
        }
        if (!evaluationTime.isAfter(lastUpdatedAt)) {
            return decayedValue;
        }
        Duration elapsed = Duration.between(lastUpdatedAt, evaluationTime);
        double elapsedSeconds = elapsed.getSeconds()
                + elapsed.getNano() / 1_000_000_000.0;
        double halfLifeSeconds = halfLife.getSeconds()
                + halfLife.getNano() / 1_000_000_000.0;
        return decayedValue * Math.pow(0.5, elapsedSeconds / halfLifeSeconds);
    }

    private static void requirePositive(Duration halfLife) {
        Objects.requireNonNull(halfLife, "halfLife");
        if (halfLife.isZero() || halfLife.isNegative()) {
            throw new IllegalArgumentException("halfLife must be positive");
        }
    }
}
