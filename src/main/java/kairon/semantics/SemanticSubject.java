package kairon.semantics;

/**
 * The separated things a semantic fact or state change can be about.
 *
 * <p>Subjects exist so that legitimate simultaneous states cannot read as a
 * contradiction. A commander standing outside a landed ship beside a deployed
 * SRV is three independent subjects, not one conflicting slot.</p>
 *
 * <p>This enum carries no importance, ranking, or comment-worthiness.</p>
 */
public enum SemanticSubject {

    /** Commander identity and account-level context. */
    COMMANDER,

    /** Where the commander physically is: in a ship, an SRV, or on foot. */
    COMMANDER_PRESENCE,

    /** The commander's own ship. */
    PRIMARY_SHIP,

    /**
     * An auxiliary vehicle known to exist for the commander.
     *
     * <p>Association does <strong>not</strong> imply occupancy.</p>
     */
    ASSOCIATED_VEHICLE,

    /**
     * The vehicle the commander is physically inside.
     *
     * <p>The canonical projection cannot currently distinguish this from
     * {@link #ASSOCIATED_VEHICLE}, so no field maps here and facts about it
     * stay unresolved.</p>
     */
    OCCUPIED_VEHICLE,

    /** The star system the commander is in. */
    CURRENT_SYSTEM,

    /**
     * Docked/landed/in-space placement.
     *
     * <p>No canonical field maps here: the only candidate, flight mode, has
     * unproven ownership and therefore lives on
     * {@link #NAVIGATION_CONTEXT}.</p>
     */
    CURRENT_LOCATION,

    /** The selected body and the facts known about it. */
    CURRENT_BODY,

    /** The organic sampling process and its supporting body facts. */
    BIOLOGICAL_SAMPLING_PROCESS,

    /**
     * Neutral navigation and flight-operation context.
     *
     * <p>Used for canonical fields whose owning subject is genuinely not
     * established by the repository. Assigning such a field to a concrete
     * subject would assert ownership the code does not prove.</p>
     */
    NAVIGATION_CONTEXT,

    /** A fact whose subject could not be established. */
    UNRESOLVED_SUBJECT
}
