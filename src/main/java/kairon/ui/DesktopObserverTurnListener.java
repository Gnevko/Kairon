package kairon.ui;

import kairon.llm.ObserverResponseValidator.ValidatedObserverResponse;
import kairon.observer.ObserverTurnListener;
import kairon.observer.ObserverTurnListener.ObservationEffectChanged;
import kairon.output.CommentSink.CommentDeliveryResult;

import java.util.Objects;

/**
 * Observer-to-GUI bridge. It performs immutable projection and immediate
 * handoff only.
 */
public final class DesktopObserverTurnListener
        implements ObserverTurnListener {

    private final KaironGuiHub guiHub;

    public DesktopObserverTurnListener(KaironGuiHub guiHub) {
        this.guiHub = Objects.requireNonNull(guiHub, "guiHub");
    }

    @Override
    public void onObservationEffectChanged(
            ObservationEffectChanged change
    ) {
        Objects.requireNonNull(change, "change");
        guiHub.postObservationEffect(
                new KaironGuiHub.ObservationEffectView(
                        change.observationId(),
                        change.busSequence(),
                        change.changedAt(),
                        change.effect().name(),
                        change.turnSequence()
                )
        );
    }

    @Override
    public void onDecisionResolved(DecisionResolved decision) {
        Objects.requireNonNull(decision, "decision");
        ValidatedObserverResponse validated =
                decision.validatedResponse();
        guiHub.postModelDecision(new KaironGuiHub.ModelDecisionView(
                decision.turnSequence(),
                decision.resolvedAt(),
                decision.eventCount(),
                validated.status().name(),
                validated.decision() == null
                        ? null
                        : validated.decision().name(),
                validated.comment(),
                // From the turn, not from the response: the model is shown no
                // event identity and asserts nothing about which observations
                // it answered.
                decision.triggerBusSequences(),
                validated.violations(),
                validated.failure(),
                decision.rawModelOutput(),
                decision.latencyMs()
        ));
    }

    @Override
    public void onTurnCompleted(TurnCompleted completed) {
        Objects.requireNonNull(completed, "completed");
        CommentDeliveryResult delivery = completed.delivery();
        guiHub.postModelCompletion(new KaironGuiHub.ModelCompletionView(
                completed.turnSequence(),
                completed.completedAt(),
                delivery.consoleOutcome().name(),
                delivery.speechResult().outcome().name(),
                delivery.deliveredForHistory(),
                completed.deliveredComment()
        ));
    }
}
