package kairon.state;

import kairon.observation.PublishedObservation;

/**
 * Mutation-capable side of canonical state projection.
 *
 * <p>Application readers receive only {@link CurrentGameStateView}; the
 * post-observation coordinator owns this interface.</p>
 */
public interface CurrentGameStateProjectionWriter
        extends CurrentGameStateView {

    CurrentGameStateProjection applyAndCapture(
            PublishedObservation<?> observation
    );
}
