package kairon.semantics;

/**
 * How a canonical value changed.
 *
 * <p>{@code UNCHANGED} is deliberately absent: absence of a change entry means
 * unchanged, so the collection stays proportional to actual change.</p>
 *
 * <p>There is no kind for "this was already true and is only now being looked
 * at". There used to be, because body facts were fields of the current body and
 * flying to the next one changed all of them at once; body facts belong to the
 * current-system registry now ({@code ADR-0025}), so every change here is
 * something an observation did.</p>
 */
public enum SemanticChangeKind {

    /** Before unknown or absent; after known. */
    ESTABLISHED,

    /** Before known; after known; the values differ. */
    UPDATED,

    /** Before known; after absent or unknown. */
    CLEARED
}
