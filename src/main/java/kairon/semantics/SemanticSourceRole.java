package kairon.semantics;

/**
 * The role the source observation had when it established a fact or change.
 *
 * <p>Three of the values classify a journal event and are declared by
 * {@link SemanticSourceRoleCatalog}; the other two are the observation kinds a
 * journal classification does not cover, because they are not journal events.
 * The observer spells the first three in its own vocabulary and reads them from
 * the catalogue — it does not decide them.</p>
 *
 * <p>The role is always taken from the observation itself. It is never
 * inferred after the fact from whether the observation reached a trigger
 * queue.</p>
 */
public enum SemanticSourceRole {

    /** A journal event eligible to start a model turn. */
    NEW,

    /** A journal event that updates state or graph but never triggers a turn. */
    CONTEXT_ONLY,

    /** A journal event kept for diagnostics only. */
    DIAGNOSTIC_ONLY,

    /** A Status snapshot observation. Not a journal event. */
    STATUS,

    /** A source lifecycle signal such as replay exhaustion. */
    CONTROL
}
