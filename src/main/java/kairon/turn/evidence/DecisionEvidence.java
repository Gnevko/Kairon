package kairon.turn.evidence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The local event ids a response may cite, and what they mean internally.
 *
 * <p>The model sees {@code 1, 2, 3}. Kairon holds the bus sequences those ids
 * stand for, and translates a response back before anything downstream sees it.
 * The mapping is valid for exactly one request: the same id means a different
 * observation in the next one, which is why it never leaves this turn.</p>
 *
 * <p>Only current-turn {@code NEW} triggers are here. A hidden observation that
 * changed state, a context fact and a graph position all remain uncitable — not
 * by a rule the validator applies, but because they have no id at all.</p>
 *
 * <h2>Why it lives in a package of its own</h2>
 * <p>Two packages need it and neither may depend on the other. The request
 * projection mints it, because it is the projection that decides which triggers
 * become events {@code 1..n}; the response validator reads it, because citing
 * an id nobody minted is the one thing a response can get wrong that Kairon can
 * check. Holding it in either of them made {@code kairon.llm} and
 * {@code kairon.observer.decision} import each other. It is a value with no
 * behaviour beyond its own invariants, so it belongs to neither.</p>
 */
public record DecisionEvidence(List<Long> triggerBusSequences) {

    public DecisionEvidence {
        triggerBusSequences = List.copyOf(Objects.requireNonNull(
                triggerBusSequences,
                "triggerBusSequences"
        ));
        if (triggerBusSequences.isEmpty()) {
            throw new IllegalArgumentException(
                    "a turn always has at least one citable event"
            );
        }
        Set<Long> seen = new HashSet<>();
        long previous = 0L;
        for (Long busSequence : triggerBusSequences) {
            Objects.requireNonNull(busSequence, "trigger busSequence");
            if (busSequence <= previous || !seen.add(busSequence)) {
                throw new IllegalArgumentException(
                        "trigger busSequences must be unique and ascending"
                );
            }
            previous = busSequence;
        }
    }

    /** How many events this request carries, and so the highest local id. */
    public int size() {
        return triggerBusSequences.size();
    }

    public boolean contains(int localId) {
        return localId >= 1 && localId <= triggerBusSequences.size();
    }

    /** The internal bus sequence behind one local id. */
    public long busSequenceOf(int localId) {
        if (!contains(localId)) {
            throw new IllegalArgumentException(
                    "unknown local event id: " + localId
            );
        }
        return triggerBusSequences.get(localId - 1);
    }

    /**
     * Translates a validated citation back to internal identity.
     *
     * <p>Callers must have rejected unknown ids first; this is the projection,
     * not the check.</p>
     */
    public List<Long> resolve(List<Integer> localIds) {
        Objects.requireNonNull(localIds, "localIds");
        List<Long> resolved = new ArrayList<>(localIds.size());
        for (Integer localId : localIds) {
            resolved.add(busSequenceOf(Objects.requireNonNull(
                    localId,
                    "local event id"
            )));
        }
        return List.copyOf(resolved);
    }
}
