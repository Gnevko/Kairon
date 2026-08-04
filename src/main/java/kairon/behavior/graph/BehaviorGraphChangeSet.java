package kairon.behavior.graph;

/**
 * Immutable value-difference summary for one graph apply boundary.
 */
public record BehaviorGraphChangeSet(
        boolean ownerChanged,
        boolean activeEpisodeChanged,
        boolean occurrenceAdded,
        boolean cursorChanged,
        boolean graphRevisionChanged,
        boolean topologyRevisionChanged
) {

    private static final BehaviorGraphChangeSet NONE =
            new BehaviorGraphChangeSet(
                    false,
                    false,
                    false,
                    false,
                    false,
                    false
            );

    public static BehaviorGraphChangeSet none() {
        return NONE;
    }

    public boolean changed() {
        return ownerChanged
                || activeEpisodeChanged
                || occurrenceAdded
                || cursorChanged
                || graphRevisionChanged
                || topologyRevisionChanged;
    }
}
