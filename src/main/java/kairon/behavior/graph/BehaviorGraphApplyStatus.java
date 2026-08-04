package kairon.behavior.graph;

/**
 * Terminal outcome of behavior-graph processing for one observation.
 */
public enum BehaviorGraphApplyStatus {
    APPLIED,
    NO_GRAPH_ID,
    DISABLED,
    NOT_APPLICABLE,
    FAILED
}
