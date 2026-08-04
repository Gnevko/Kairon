package kairon.observer.decision;

import kairon.projection.ProjectedObservation;
import kairon.semantics.SemanticEffectAccumulator;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The immutable turn inputs a decision request is built from, and nothing else.
 *
 * <p>Everything here was fixed by the coordinator in the same critical section
 * that closed the trigger batch. The factory that consumes it never reads the
 * state projector, a graph service, the observation bus, a mutable queue, a
 * clock or the trace writer.</p>
 *
 * @param turnSequence     the sequence of the turn being prepared. Internal
 *                         correlation for the trace; never sent to the model.
 * @param triggers         the ordered current-turn NEW observations. Their
 *                         positions become the local event ids the model sees.
 * @param semanticEffects  everything drained through the final trigger,
 *                         including the triggers' own envelopes
 * @param previousComments up to three previously delivered comments, oldest
 *                         first, for the local repetition check only
 */
public record DecisionTurnInputs(
        long turnSequence,
        List<ProjectedObservation> triggers,
        SemanticEffectAccumulator.Drained semanticEffects,
        List<DeliveredModelComment> previousComments
) {

    /** Internal duplicate-comment memory bound; not model-facing. */
    public static final int MAX_PREVIOUS_COMMENTS = 3;

    public DecisionTurnInputs {
        if (turnSequence < 1) {
            throw new IllegalArgumentException(
                    "turnSequence must be positive"
            );
        }
        triggers = List.copyOf(Objects.requireNonNull(triggers, "triggers"));
        semanticEffects = Objects.requireNonNull(
                semanticEffects,
                "semanticEffects"
        );
        previousComments = List.copyOf(Objects.requireNonNull(
                previousComments,
                "previousComments"
        ));
        if (triggers.isEmpty()) {
            throw new IllegalArgumentException(
                    "a model turn requires at least one NEW trigger"
            );
        }
        if (previousComments.size() > MAX_PREVIOUS_COMMENTS) {
            throw new IllegalArgumentException(
                    "at most three previous comments are allowed"
            );
        }
        Set<Long> seen = new HashSet<>();
        long previous = 0L;
        for (ProjectedObservation trigger : triggers) {
            Objects.requireNonNull(trigger, "NEW trigger");
            if (trigger.busSequence() <= previous
                    || !seen.add(trigger.busSequence())) {
                throw new IllegalArgumentException(
                        "NEW triggers must use unique ascending busSequence"
                );
            }
            previous = trigger.busSequence();
        }
    }

    /** The observation whose captured state applies to the whole turn. */
    public ProjectedObservation finalTrigger() {
        return triggers.getLast();
    }
}
