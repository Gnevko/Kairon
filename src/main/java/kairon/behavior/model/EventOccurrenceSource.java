package kairon.behavior.model;

/**
 * What kind of observation the graph layer accepted to create one occurrence.
 *
 * <p>Recorded at acceptance, never derived afterwards. In particular it is
 * <strong>not</strong> a function of the normalized event type: a journal event
 * and a Status snapshot can normalize to the same type, and only the source
 * tells them apart.</p>
 *
 * <p>This carries no significance, no importance and no ordering. It changes
 * nothing about normalization, significance, transitions, probabilities or
 * persistence identity. It answers exactly one question: which kind of external
 * observation produced this occurrence.</p>
 *
 * <p>Deliberately closed to the three variants that have a production write
 * path today. A fourth value may only be added when a fourth writer exists.</p>
 */
public enum EventOccurrenceSource {

    /** A journal event, normalized by {@code BehaviorEventNormalizer}. */
    JOURNAL,

    /**
     * A Status snapshot delta derived by {@code StatusStateDeltaAdapter}.
     *
     * <p>Never a model trigger, but it does enter the active-episode timeline
     * and can own the graph cursor.</p>
     */
    STATUS,

    /**
     * An occurrence Kairon synthesised itself.
     *
     * <p>Today the only writer is the ship-switch episode root, which has no
     * originating external observation of its own.</p>
     */
    SYNTHETIC
}
