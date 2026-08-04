package kairon.behavior.snapshot;

/**
 * Availability of the graph situation captured after one graph apply.
 */
public enum BehaviorSituationCaptureStatus {
    AVAILABLE,
    UNCHANGED,
    NO_ACTIVE_GRAPH,
    NO_ACTIVE_EPISODE,
    GRAPH_DISABLED,
    NO_GRAPH_ID,
    GRAPH_APPLY_FAILED,
    SNAPSHOT_FAILED,
    INCONSISTENT
}
