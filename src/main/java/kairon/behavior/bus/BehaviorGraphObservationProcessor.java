package kairon.behavior.bus;

import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.graph.BehaviorGraphProcessor;
import kairon.behavior.graph.BehaviorGraphService;
import kairon.behavior.status.StatusStateDeltaAdapter;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.source.ObservationSourceSignal
        .ObservationSourceSignalType;
import kairon.observation.status.StatusSnapshotObservation;
import kairon.state.CurrentGameStateProjection;

import java.util.Objects;

/**
 * Synchronous behavior-specific dispatcher inside the projection boundary.
 *
 * <p>The coordinator supplies FIFO execution. This component retains the
 * ordered Status delta state and delegates all graph semantics to
 * {@link BehaviorGraphService}.</p>
 */
public final class BehaviorGraphObservationProcessor
        implements BehaviorGraphProcessor {

    private final BehaviorGraphService graphService;
    private final StatusStateDeltaAdapter statusDeltaAdapter =
            new StatusStateDeltaAdapter();

    public BehaviorGraphObservationProcessor(
            BehaviorGraphService graphService
    ) {
        this.graphService = Objects.requireNonNull(
                graphService,
                "graphService"
        );
    }

    @Override
    public BehaviorGraphApplyResult apply(
            PublishedObservation<?> observation,
            CurrentGameStateProjection stateProjection
    ) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(stateProjection, "stateProjection");
        if (observation.busSequence() != stateProjection.busSequence()) {
            throw new IllegalArgumentException(
                    "state projection does not belong to observation"
            );
        }
        if (observation.payload() instanceof JournalEventObservation) {
            return graphService.onObservation(
                    journalObservation(observation),
                    stateProjection.currentState(),
                    stateProjection.observationContext()
            );
        }
        if (observation.payload() instanceof StatusSnapshotObservation) {
            PublishedObservation<StatusSnapshotObservation> status =
                    statusObservation(observation);
            return graphService.onStatusDeltas(
                    status,
                    statusDeltaAdapter.adapt(status),
                    stateProjection.currentState()
            );
        }
        if (observation.payload() instanceof ObservationSourceSignal signal) {
            requireReplayExhaustion(observation, signal);
            return graphService.completeReplay(
                    observation,
                    stateProjection.currentState()
            );
        }
        return graphService.onNotApplicable(
                observation,
                stateProjection.currentState()
        );
    }

    @Override
    public void close() {
        graphService.closeSource();
    }

    @SuppressWarnings("unchecked")
    private static PublishedObservation<? extends JournalEventObservation>
            journalObservation(PublishedObservation<?> observation) {
        return (PublishedObservation<? extends JournalEventObservation>)
                observation;
    }

    @SuppressWarnings("unchecked")
    private static PublishedObservation<StatusSnapshotObservation>
            statusObservation(PublishedObservation<?> observation) {
        return (PublishedObservation<StatusSnapshotObservation>) observation;
    }

    private static void requireReplayExhaustion(
            PublishedObservation<?> observation,
            ObservationSourceSignal signal
    ) {
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
    }
}
