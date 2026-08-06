package kairon.projection;

import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.observation.PublishedObservation;
import kairon.semantics.SemanticObservationEnvelope;
import kairon.state.AppliedObservation;
import kairon.state.CurrentGameStateSnapshot;
import kairon.state.CurrentGameStateChangeSet;
import kairon.system.SystemRegistrySnapshot;

import java.util.Objects;

/**
 * Immutable downstream envelope created after state and graph processing.
 *
 * <p>{@code semanticEnvelope} belongs to the same post-projection moment as
 * {@code applied}, {@code behaviorSituation} and {@code systemRegistry}: all of
 * them are captured before publication and none may be re-read afterwards.</p>
 *
 * <p>{@code applied} owns the canonical half of that moment — the state before
 * and after, the exact delta, and what the observation means — so
 * {@link #currentState()} reads out of it rather than beside it.</p>
 *
 * <p>{@code systemRegistry} is the star system the Commander is in, as it stood
 * after this observation. It travels here rather than being asked for later for
 * the same reason the behaviour situation does: by the time a decision request
 * is built, the live registry has moved on.</p>
 */
public record ProjectedObservation(
        PublishedObservation<?> trigger,
        AppliedObservation applied,
        CurrentGameStateChangeSet stateChanges,
        BehaviorGraphApplyResult graphResult,
        BehaviorSituationSnapshot behaviorSituation,
        SemanticObservationEnvelope semanticEnvelope,
        SystemRegistrySnapshot systemRegistry
) {

    public ProjectedObservation {
        trigger = Objects.requireNonNull(trigger, "trigger");
        applied = Objects.requireNonNull(applied, "applied");
        stateChanges = Objects.requireNonNull(
                stateChanges,
                "stateChanges"
        );
        graphResult = Objects.requireNonNull(graphResult, "graphResult");
        behaviorSituation = Objects.requireNonNull(
                behaviorSituation,
                "behaviorSituation"
        );
        semanticEnvelope = Objects.requireNonNull(
                semanticEnvelope,
                "semanticEnvelope"
        );
        systemRegistry = Objects.requireNonNull(
                systemRegistry,
                "systemRegistry"
        );
        if (trigger.busSequence() != systemRegistry.busSequence()) {
            throw new IllegalArgumentException(
                    "system registry snapshot does not belong to trigger"
            );
        }
        if (trigger.busSequence() != semanticEnvelope.busSequence()) {
            throw new IllegalArgumentException(
                    "semantic envelope does not belong to trigger"
            );
        }
        if (trigger.busSequence() != applied.busSequence()) {
            throw new IllegalArgumentException(
                    "applied observation does not belong to trigger"
            );
        }
        if (trigger.busSequence() != graphResult.busSequence()) {
            throw new IllegalArgumentException(
                    "graph result does not belong to trigger"
            );
        }
        if (trigger.busSequence() != behaviorSituation.busSequence()) {
            throw new IllegalArgumentException(
                    "behavior situation does not belong to trigger"
            );
        }
        if (!graphResult.equals(behaviorSituation.applyResult())) {
            throw new IllegalArgumentException(
                    "behavior situation does not match graph result"
            );
        }
    }

    public long busSequence() {
        return trigger.busSequence();
    }

    /** Canonical state as it stood after this observation was applied. */
    public CurrentGameStateSnapshot currentState() {
        return applied.currentState();
    }
}
