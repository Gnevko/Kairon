package kairon.observer;

import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.snapshot.BehaviorSituationCaptureStatus;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.observation.ObservationPayload;
import kairon.observation.PublishedObservation;
import kairon.observation.bus.ObservationBus;
import kairon.observation.bus.ObservationBus.ObservationSubscription;
import kairon.projection.ProjectedObservation;
import kairon.projection.ProjectedObservationBus;
import kairon.projection.SemanticEnvelopeFactory;
import kairon.state.CurrentGameStateProjection;
import kairon.state.CurrentGameStateProjector;
import kairon.system.SystemRegistrySnapshot;

/**
 * Synchronous test-only bridge for observer delivery and speech tests.
 *
 * <p>Projection concurrency and production handoff are covered separately;
 * this fixture keeps downstream tests deterministic.</p>
 */
final class ProjectedObservationTestBridge implements AutoCloseable {

    static final String RAW_SUBSCRIBER_ID =
            "test-observation-projection";

    private final CurrentGameStateProjector state =
            new CurrentGameStateProjector();
    private final ProjectedObservationBus projectedBus =
            new ProjectedObservationBus();
    private final LlmJournalObserverSubscriber.Subscriptions
            llmSubscriptions;
    private final ObservationSubscription rawSubscription;

    ProjectedObservationTestBridge(
            ObservationBus rawBus,
            ObserverTurnCoordinator coordinator
    ) {
        llmSubscriptions =
                new LlmJournalObserverSubscriber(coordinator)
                        .subscribeTo(projectedBus);
        rawSubscription = rawBus.subscribe(
                RAW_SUBSCRIBER_ID,
                ObservationPayload.class,
                this::project
        );
    }

    LlmJournalObserverSubscriber.Subscriptions llmSubscriptions() {
        return llmSubscriptions;
    }

    @Override
    public void close() {
        rawSubscription.close();
        llmSubscriptions.close();
        projectedBus.close();
    }

    private void project(
            PublishedObservation<ObservationPayload> observation
    ) {
        CurrentGameStateProjection projection =
                state.applyAndCapture(observation);
        BehaviorGraphApplyResult graphResult =
                BehaviorGraphApplyResult.disabled(
                        observation.busSequence()
                );
        projectedBus.publish(new ProjectedObservation(
                observation,
                projection.applied(),
                projection.changes(),
                graphResult,
                BehaviorSituationSnapshot.unavailable(
                        graphResult,
                        BehaviorSituationCaptureStatus.GRAPH_DISABLED
                ),
                SemanticEnvelopeFactory.production().create(
                        observation,
                        projection.applied()
                ),
                SystemRegistrySnapshot.empty(observation.busSequence())
        ));
    }
}
