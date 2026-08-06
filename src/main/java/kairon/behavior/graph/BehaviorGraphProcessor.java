package kairon.behavior.graph;

import kairon.behavior.context.BodyDetailLookup;
import kairon.observation.PublishedObservation;
import kairon.state.CurrentGameStateProjection;

/**
 * Sequential behavior-graph side of the post-observation boundary.
 *
 * <p>The body detail arrives with the observation rather than being fetched.
 * It is the current system's, and the graph is not the projection that keeps
 * the current system — whoever holds both hands over one immutable answer, so
 * that neither peer projection ever reads the other.</p>
 */
public interface BehaviorGraphProcessor extends AutoCloseable {

    BehaviorGraphApplyResult apply(
            PublishedObservation<?> observation,
            CurrentGameStateProjection stateProjection,
            BodyDetailLookup bodies
    );

    @Override
    void close();
}
