package kairon.semantics;

/**
 * How a canonical value changed.
 *
 * <p>{@code UNCHANGED} is deliberately absent: absence of a change entry means
 * unchanged, so the collection stays proportional to actual change.</p>
 */
public enum SemanticChangeKind {

    /** Before unknown or absent; after known. */
    ESTABLISHED,

    /** Before known; after known; the values differ. */
    UPDATED,

    /** Before known; after absent or unknown. */
    CLEARED,

    /**
     * The observation selected or activated an already stored registry fact.
     *
     * <p>The fact was <strong>not</strong> newly learned by this observation.
     * Determined by the projector write path, never by comparing values: a
     * re-visited body whose stored value happens to equal a freshly observed
     * one is indistinguishable by value alone.</p>
     */
    ACTIVATED_FROM_CONTEXT
}
