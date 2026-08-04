package kairon.behavior.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-ship aggregate of all observed transitions with the same type endpoints.
 */
public record TransitionEdge(
        EdgeKey key,
        WeightedCounter globalCounter,
        List<ContextCounter> contextCounters,
        Instant firstSeenAt,
        Instant lastSeenAt
) implements Comparable<TransitionEdge> {

    public TransitionEdge {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(globalCounter, "globalCounter");
        if (globalCounter.rawCount() < 1) {
            throw new IllegalArgumentException(
                    "edge global counter must be nonempty"
            );
        }
        contextCounters = sortedContextCounters(contextCounters);
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    }

    public static TransitionEdge first(
            EdgeKey key,
            ContextKey contextKey,
            Instant observedAt,
            Duration halfLife
    ) {
        WeightedCounter first = WeightedCounter.empty()
                .record(observedAt, halfLife);
        return new TransitionEdge(
                key,
                first,
                List.of(new ContextCounter(contextKey, first)),
                observedAt,
                observedAt
        );
    }

    public TransitionEdge record(
            ContextKey contextKey,
            Instant observedAt,
            Duration halfLife
    ) {
        Objects.requireNonNull(contextKey, "contextKey");
        Objects.requireNonNull(observedAt, "observedAt");
        List<ContextCounter> updated = new ArrayList<>(contextCounters);
        int found = -1;
        for (int index = 0; index < updated.size(); index++) {
            if (updated.get(index).key().equals(contextKey)) {
                found = index;
                break;
            }
        }
        if (found >= 0) {
            ContextCounter current = updated.get(found);
            updated.set(
                    found,
                    new ContextCounter(
                            contextKey,
                            current.counter().record(observedAt, halfLife)
                    )
            );
        } else {
            updated.add(new ContextCounter(
                    contextKey,
                    WeightedCounter.empty().record(observedAt, halfLife)
            ));
        }
        return new TransitionEdge(
                key,
                globalCounter.record(observedAt, halfLife),
                updated,
                firstSeenAt.isAfter(observedAt) ? observedAt : firstSeenAt,
                lastSeenAt.isBefore(observedAt) ? observedAt : lastSeenAt
        );
    }

    public Optional<WeightedCounter> contextCounter(ContextKey contextKey) {
        return contextCounters.stream()
                .filter(entry -> entry.key().equals(contextKey))
                .map(ContextCounter::counter)
                .findFirst();
    }

    @Override
    public int compareTo(TransitionEdge other) {
        return key.compareTo(other.key);
    }

    private static List<ContextCounter> sortedContextCounters(
            List<ContextCounter> counters
    ) {
        Objects.requireNonNull(counters, "contextCounters");
        List<ContextCounter> copy = new ArrayList<>(counters);
        copy.sort(Comparator.naturalOrder());
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).key().equals(copy.get(index).key())) {
                throw new IllegalArgumentException(
                        "contextCounters must have unique keys"
                );
            }
        }
        return List.copyOf(copy);
    }

    public record ContextCounter(
            ContextKey key,
            WeightedCounter counter
    ) implements Comparable<ContextCounter> {

        public ContextCounter {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(counter, "counter");
            if (counter.rawCount() < 1) {
                throw new IllegalArgumentException(
                        "context counter must be nonempty"
                );
            }
        }

        @Override
        public int compareTo(ContextCounter other) {
            return key.compareTo(other.key);
        }
    }
}
