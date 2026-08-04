package kairon.behavior.snapshot;

/**
 * Signals that committed graph metadata differs from captured graph state.
 */
public final class BehaviorSituationInconsistencyException
        extends IllegalStateException {

    public BehaviorSituationInconsistencyException(String message) {
        super(message);
    }
}
