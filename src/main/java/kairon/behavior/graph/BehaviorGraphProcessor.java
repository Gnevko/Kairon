package kairon.behavior.graph;

import kairon.observation.PublishedObservation;
import kairon.state.CurrentGameStateProjection;

/**
 * Sequential behavior-graph side of the post-observation boundary.
 */
public interface BehaviorGraphProcessor extends AutoCloseable {

    BehaviorGraphApplyResult apply(
            PublishedObservation<?> observation,
            CurrentGameStateProjection stateProjection
    );

    @Override
    void close();
}
