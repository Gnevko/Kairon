package kairon.semantics;

import kairon.observation.journal.JournalEventObservation;

import java.util.List;

/**
 * Derives structured facts for one journal event type.
 *
 * <p>Adapters read {@link JournalEventObservation#raw()} directly: the record's
 * own JSON is the source of meaning, and any rendering of it is not.</p>
 *
 * <p>An adapter is mechanism-oriented. It describes what a class of events
 * means, not how one replay fixture should look.</p>
 */
@FunctionalInterface
public interface SemanticEventAdapter {

    /**
     * Returns the facts this event establishes, in deterministic order.
     *
     * <p>Returning an empty list is valid and means the event carries no
     * derivable gameplay fact. Adapters report gaps through
     * {@link Result#unresolved()} rather than by throwing.</p>
     */
    Result adapt(
            JournalEventObservation event,
            SemanticProvenance provenance
    );

    /** Structured facts plus explicitly recorded gaps. */
    record Result(
            List<SemanticFact> facts,
            List<UnresolvedFact> unresolved
    ) {

        public Result {
            facts = List.copyOf(facts);
            unresolved = List.copyOf(unresolved);
        }

        public static Result empty() {
            return new Result(List.of(), List.of());
        }

        public static Result of(SemanticFact... facts) {
            return new Result(List.of(facts), List.of());
        }

        public static Result of(
                List<SemanticFact> facts,
                List<UnresolvedFact> unresolved
        ) {
            return new Result(facts, unresolved);
        }
    }
}
