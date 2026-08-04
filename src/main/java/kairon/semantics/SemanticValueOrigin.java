package kairon.semantics;

/**
 * Where the after-value of a state change was determined.
 *
 * <p>This is write-path metadata produced inside the projection boundary. It
 * cannot be reconstructed downstream, because a stored value and a freshly
 * observed value can be identical.</p>
 */
public enum SemanticValueOrigin {

    /** This observation directly wrote the underlying field or registry entry. */
    OBSERVATION,

    /**
     * The value was served from the projector's stored per-body registry
     * rather than written by this observation.
     */
    STORED_CONTEXT
}
