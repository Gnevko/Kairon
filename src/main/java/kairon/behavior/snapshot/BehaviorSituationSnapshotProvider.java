package kairon.behavior.snapshot;

import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.observation.PublishedObservation;
import kairon.state.CurrentGameStateSnapshot;

/**
 * Read-only capture boundary for one committed graph observation.
 */
@FunctionalInterface
public interface BehaviorSituationSnapshotProvider {

    BehaviorSituationSnapshot capture(
            PublishedObservation<?> trigger,
            CurrentGameStateSnapshot currentState,
            BehaviorGraphApplyResult applyResult
    );
}
