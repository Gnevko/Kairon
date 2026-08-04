package kairon.semantics;

/**
 * The controlled verb of a structured fact.
 *
 * <p>Closed on purpose. An adapter cannot invent a verb, and a consumer cannot
 * be surprised by one. Polarity that today lives only in Java class identity —
 * {@code MissionCompleted} versus {@code MissionFailed}, {@code DockingGranted}
 * versus {@code DockingDenied} — becomes an explicit operation plus the
 * {@code completion} and {@code negation} fields of
 * {@link SemanticFact}.</p>
 *
 * <p>Only verbs required by an implemented adapter appear here.</p>
 */
public enum SemanticOperation {

    /** The observation carries no derivable operation. */
    UNSPECIFIED,

    ENTERED,
    EXITED,
    ARRIVED,
    DEPARTED,
    APPROACHED,
    LEFT,

    LANDED,
    LIFTED_OFF,
    DOCKED,
    UNDOCKED,
    DOCKING_REQUESTED,
    DOCKING_GRANTED,
    DOCKING_DENIED,
    DOCKING_CANCELLED,
    DOCKING_TIMED_OUT,

    LAUNCHED,
    RECOVERED,
    BOARDED,
    DISEMBARKED,

    SCANNED,
    SURVEYED,
    SAMPLED,
    RECORDED,
    TARGETED,

    ACCEPTED,
    COMPLETED,
    FAILED,
    ABANDONED,

    BOUGHT,
    SOLD,

    RECEIVED,
    IDENTIFIED,

    ACQUIRED,
    DECOMMISSIONED,
    RENAMED,
    SCHEDULED,
    CANCELLED,
    DEPLOYED,
    CLAIMED,
    RELEASED,
    CONTRIBUTED,

    INTERDICTED,
    ESCAPED,
    ATTACKED,
    KILLED,
    DIED,
    DAMAGED,
    BREACHED,
    DESTROYED,
    SHUT_DOWN,
    REWARDED,
    COMMITTED,

    CRAFTED,
    UNLOCKED,
    CONVERTED,
    UPGRADED,
    HACKED,
    REPAIRED,
    PROMOTED,
    DEMOTED,
    JOINED,
    HIRED,
    FIRED,
    EXPELLED,
    CREATED,
    DEFECTED,
    REDEEMED,
    TRANSFERRED,
    SWITCHED_TO,

    MAPPED,
    SURVEY_COMPLETED,
    COLLECTED,
    EJECTED,
    DISCOVERED,
    CRACKED,
    BOOSTED,
    DROPPED,
    REDIRECTED
}
