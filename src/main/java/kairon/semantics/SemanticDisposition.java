package kairon.semantics;

/**
 * What Kairon has decided about a catalogued journal event type's semantics.
 *
 * <p>Every known catalogue type resolves to exactly one of these. A missing
 * adapter is not a disposition: it is a coverage failure, caught by the
 * coverage guard rather than tolerated at runtime.</p>
 */
public enum SemanticDisposition {

    /**
     * An adapter supplies every critical fact the model-facing contract needs
     * for this event.
     */
    STRUCTURED,

    /**
     * The event may reach a turn, but its critical decision semantics are
     * fully represented by the exact state delta, or the event genuinely
     * carries no further critical facts.
     */
    NO_CRITICAL_STRUCTURED_FACTS,

    /**
     * The event is model-eligible, but the repository and the available
     * authoritative evidence do not allow part of its critical semantics to be
     * built safely, so that part is left explicitly unresolved rather than
     * guessed.
     */
    UNRESOLVED_AUTHORITATIVE_SEMANTICS,

    /**
     * The event is not model input under the current selection policy.
     *
     * <p>Independent of whether an adapter happens to exist: several
     * diagnostic types are adapted because they belong to a mechanism that is
     * adapted as a whole.</p>
     */
    DIAGNOSTIC_ONLY
}
