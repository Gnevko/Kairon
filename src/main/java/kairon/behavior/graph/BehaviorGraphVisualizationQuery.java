package kairon.behavior.graph;

import kairon.behavior.model.GraphId;

import java.time.Instant;
import java.util.Optional;

/**
 * Testable read-only boundary used by behavior graph presentation code.
 */
public interface BehaviorGraphVisualizationQuery
        extends BehaviorGraphOccurrenceQuery {

    Optional<GraphId> getActiveGraphId();

    Optional<BehaviorGraphVisualizationSnapshot> getVisualizationSnapshot(
            GraphId graphId,
            Instant evaluationTime
    );
}
