package kairon.observer;

import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.source.ObservationSourceSignal
        .ObservationSourceSignalType;
import kairon.projection.ProjectedObservation;
import kairon.projection.ProjectedObservationBus;
import kairon.semantics.SemanticSourceRole;
import kairon.semantics.SystemVisitPolicy;
import kairon.state.CurrentGameStateSnapshot;

import java.util.Objects;

/**
 * The only bridge from completed projections into observer-owned processing.
 *
 * <p>Every projection contributes its semantic effect; only an admitted
 * {@code NEW} journal event additionally becomes a trigger. The two paths are
 * distinct and an observation never travels both as an effect twice.</p>
 */
public final class LlmJournalObserverSubscriber {

    public static final String SUBSCRIBER_ID =
            "llm-journal-observer";

    private final ObserverTurnCoordinator coordinator;

    /**
     * Derived state this subscriber owns, mutated only on the delivery path.
     *
     * <p>Projections arrive on one ordered bus, so the memory advances in
     * source order and never mutates a publication.</p>
     */
    private final BodySurveyNoveltyGuard bodySurveyGuard =
            new BodySurveyNoveltyGuard();

    public LlmJournalObserverSubscriber(ObserverTurnCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    public Subscriptions subscribeTo(ProjectedObservationBus bus) {
        Objects.requireNonNull(bus, "bus");
        return new Subscriptions(bus.subscribe(
                SUBSCRIBER_ID,
                this::onProjectedObservation
        ));
    }

    private void onProjectedObservation(
            ProjectedObservation projectedObservation
    ) {
        Objects.requireNonNull(
                projectedObservation,
                "projectedObservation"
        );

        // Recorded first, and before any command that can start a turn, so a
        // turn never begins without the effects that preceded its final
        // trigger. The role is read from the immutable envelope rather than
        // reclassified here: there is exactly one source-role classifier.
        coordinator.post(new ObserverCommand.RecordSemanticEffect(
                projectedObservation.semanticEnvelope()
        ));

        // Where the Commander is is true whatever the capture mode and
        // whatever the role, so the visit is followed for every journal
        // observation. Only what the model was told is capture-dependent, and
        // that is decided in queueTrigger.
        trackVisitBoundary(projectedObservation);

        SemanticSourceRole role =
                projectedObservation.semanticEnvelope().sourceRole();
        switch (role) {
            case NEW -> queueTrigger(projectedObservation);
            case CONTROL -> onSourceSignal(projectedObservation);
            case CONTEXT_ONLY, DIAGNOSTIC_ONLY, STATUS -> {
                // Their effects are recorded above. They never become
                // triggers: event selection is unchanged by this path, and no
                // context-only type is a scanner result.
            }
        }
    }

    private void trackVisitBoundary(
            ProjectedObservation projectedObservation
    ) {
        if (!(projectedObservation.trigger().payload()
                instanceof JournalEventObservation event)) {
            return;
        }
        CurrentGameStateSnapshot state = projectedObservation.currentState();
        bodySurveyGuard.trackVisitBoundary(
                event,
                state.commanderFid(),
                state.shipId(),
                state.systemAddress()
        );
    }

    /**
     * Admits a {@code NEW} projection into the trigger queue, or does not.
     *
     * <p>Three reasons to decline, and none reclassifies the observation: its
     * semantic effect was already recorded above, and its role on the envelope
     * is untouched. Historical {@code BOOTSTRAP} capture is model-silent,
     * {@link LlmJournalEventSelection#admitsAsTrigger} answers what a type
     * alone cannot say about one record, and {@link BodySurveyNoveltyGuard}
     * answers what no single record can say — whether this scanner result is
     * the one the model was already given.</p>
     *
     * <p>The order of the three is the point. Capture mode is checked first,
     * so a historical result the model is never shown does not enter the
     * novelty memory and cannot silence the live reading that repeats it.</p>
     */
    private void queueTrigger(ProjectedObservation projectedObservation) {
        if (projectedObservation.trigger().captureMode()
                == ObservationCaptureMode.BOOTSTRAP) {
            return;
        }
        if (!(projectedObservation.trigger().payload()
                instanceof JournalEventObservation event)
                || !LlmJournalEventSelection.admitsAsTrigger(event)) {
            return;
        }
        if (!bodySurveyGuard.admits(event)) {
            return;
        }
        coordinator.post(new ObserverCommand.QueueNewObservation(
                projectedObservation
        ));
    }

    private void onSourceSignal(ProjectedObservation projectedObservation) {
        PublishedObservation<?> observation =
                projectedObservation.trigger();
        if (!(observation.payload()
                instanceof ObservationSourceSignal signal)) {
            throw new IllegalArgumentException(
                    "control role requires an observation source signal"
            );
        }
        if (signal.signalType()
                != ObservationSourceSignalType.REPLAY_SOURCE_EXHAUSTED) {
            throw new IllegalArgumentException(
                    "unsupported observation source signal"
            );
        }
        if (observation.captureMode() != ObservationCaptureMode.REPLAY) {
            throw new IllegalArgumentException(
                    "replay exhaustion signal must use REPLAY capture mode"
            );
        }
        // The graph completes its episode on the same signal. A novelty memory
        // that outlived the source would carry one run's findings into the
        // next, which is the disagreement the shared visit policy exists to
        // prevent.
        bodySurveyGuard.endVisit(SystemVisitPolicy.replayCompleted());
        coordinator.post(new ObserverCommand.ReplaySourceExhausted(
                sourceSignalObservation(observation)
        ));
    }

    @SuppressWarnings("unchecked")
    private static PublishedObservation<ObservationSourceSignal>
            sourceSignalObservation(PublishedObservation<?> observation) {
        return (PublishedObservation<ObservationSourceSignal>) observation;
    }

    public record Subscriptions(
            ProjectedObservationBus.Subscription projectedObservations
    ) implements AutoCloseable {

        public Subscriptions {
            Objects.requireNonNull(
                    projectedObservations,
                    "projectedObservations"
            );
        }

        public boolean allActive() {
            return projectedObservations.isActive();
        }

        public boolean ownsSubscriberId(String subscriberId) {
            return projectedObservations.subscriberId().equals(
                    Objects.requireNonNull(subscriberId, "subscriberId")
            );
        }

        @Override
        public void close() {
            projectedObservations.close();
        }
    }
}
