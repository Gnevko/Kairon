package kairon.state;

import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.PublishedObservation;
import kairon.semantics.EffectRetention;
import kairon.semantics.ObservationSemantics;
import kairon.semantics.SemanticSourceRole;
import kairon.semantics.SemanticSourceRoles;
import kairon.semantics.SemanticStateChange;

import java.util.List;
import java.util.Objects;

/**
 * One observation, after it has been applied to canonical state, with what it
 * means.
 *
 * <p>The whole of one post-event moment in one immutable value: which
 * observation it was, how it was captured, what it did to what Kairon knows, the
 * state before and after, and the exact delta between them. Everything is copied
 * from what the projection produced; nothing here is recomputed later and
 * nothing can be reconstructed downstream, because {@code previousState} exists
 * only inside the projection boundary.</p>
 *
 * <p>Independent of everything below it: no provider, no graph store, no
 * model-facing JSON. It is produced identically whether the behaviour graph is
 * running or not, and for journal and Status observations alike.</p>
 *
 * <h2>What is decided on it, and what is not</h2>
 * <p>{@code effectRetention} is read: the effect accumulator drops a
 * {@code RESTORE_ONLY} observation's effects rather than carrying them into a
 * later turn. That is the one decision this value owns.</p>
 *
 * <p>Nothing else classified is carried. An application mode and a model
 * visibility were once computed here and travelled the whole pipeline without a
 * reader: the graph classifies through its own significance policy, the observer
 * selects through its own profile, and the novelty guards keep their own
 * memories. A second classification nobody consults is a second answer waiting
 * to disagree with the one in force, so it is not carried. Moving one of those
 * decisions here later is a behaviour change and has to be argued as one.</p>
 *
 * <p>There is no visit identity here. A visit is owned by the behaviour graph as
 * a {@code SystemEpisode} and, separately, by the observer's novelty guard;
 * neither has seen this observation at the moment it is applied, and the graph
 * can be switched off entirely. Deriving one here would be a third independent
 * counter, which is the defect rather than the fix.</p>
 */
public record AppliedObservation(
        long busSequence,
        String observationId,
        String rawObservationType,
        ObservationCaptureMode captureMode,
        SemanticSourceRole sourceRole,
        EffectRetention effectRetention,
        CurrentGameStateSnapshot previousState,
        CurrentGameStateSnapshot currentState,
        CurrentGameStateSnapshot observationContext,
        List<SemanticStateChange> semanticChanges
) {

    public AppliedObservation {
        if (busSequence < 1) {
            throw new IllegalArgumentException("busSequence must be positive");
        }
        observationId = requireNonBlank(observationId, "observationId");
        rawObservationType = requireNonBlank(
                rawObservationType,
                "rawObservationType"
        );
        captureMode = Objects.requireNonNull(captureMode, "captureMode");
        sourceRole = Objects.requireNonNull(sourceRole, "sourceRole");
        effectRetention = Objects.requireNonNull(
                effectRetention,
                "effectRetention"
        );
        previousState = Objects.requireNonNull(previousState, "previousState");
        currentState = Objects.requireNonNull(currentState, "currentState");
        observationContext = Objects.requireNonNull(
                observationContext,
                "observationContext"
        );
        semanticChanges = List.copyOf(Objects.requireNonNull(
                semanticChanges,
                "semanticChanges"
        ));
        for (SemanticStateChange change : semanticChanges) {
            if (change.provenance().busSequence() != busSequence) {
                throw new IllegalArgumentException(
                        "semantic change does not belong to this observation"
                );
            }
        }
    }

    /**
     * Classifies and packages one applied observation.
     *
     * <p>The identity and capture metadata are read from the publication and the
     * effect retention from {@link ObservationSemantics}, so no caller decides
     * either and no caller can disagree about them.</p>
     */
    public static AppliedObservation of(
            PublishedObservation<?> observation,
            CurrentGameStateSnapshot previousState,
            CurrentGameStateSnapshot currentState,
            CurrentGameStateSnapshot observationContext,
            List<SemanticStateChange> semanticChanges
    ) {
        Objects.requireNonNull(observation, "observation");
        return new AppliedObservation(
                observation.busSequence(),
                observation.observationId(),
                SemanticSourceRoles.rawObservationTypeOf(
                        observation.payload()
                ),
                observation.captureMode(),
                SemanticSourceRoles.roleOf(observation.payload()),
                ObservationSemantics.retentionOf(observation.captureMode()),
                previousState,
                currentState,
                observationContext,
                semanticChanges
        );
    }

    /** Whether this observation changed anything Kairon knows. */
    public boolean changedState() {
        return !semanticChanges.isEmpty();
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
