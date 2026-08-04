package kairon.semantics;

/**
 * How long one observation's semantic effects are of interest.
 *
 * <p>Separate from {@link ModelVisibility} on purpose. Visibility answers
 * whether the model may be told about the observation itself; this answers
 * whether its effects are news to a <em>later</em> turn. A live
 * {@code CONTEXT_ONLY} record is model-silent and its effects are exactly what
 * the accumulator exists to keep — the two questions have different answers for
 * the same observation, so one enum cannot carry both.</p>
 *
 * <p>It also has nothing to say about canonical state. Every observation is
 * applied, whatever this says; what is decided here is only whether the delta
 * survives past the moment it was applied in.</p>
 */
public enum EffectRetention {

    /**
     * The effects belong to the next turn.
     *
     * <p>Something happened while Kairon was listening, and the turn that comes
     * next is entitled to say so — including for an observation the model never
     * hears about directly.</p>
     */
    RETAIN_FOR_TURN,

    /**
     * The effects restored what was already true and end there.
     *
     * <p>A historical record read to catch up on a session in progress. It
     * establishes canonical state, and that state is standing background from
     * the first turn onwards — but nothing about it happened between two turns,
     * so it is not a change in any of them.</p>
     */
    RESTORE_ONLY
}
