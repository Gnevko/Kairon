package kairon.semantics;

import java.util.Objects;

/**
 * What one observation does to the visit in progress.
 *
 * <p>A visit is one look at one system. Two layers keep their own memory of it
 * — the behaviour graph as a {@code SystemEpisode} with its timeline and its
 * persistence, the observer as the memory of which scanner results it has
 * already been told — and they must begin and end that memory on the same
 * observations. When they did not, a finding the graph had just recorded was
 * silenced by a memory that outlived the visit.</p>
 *
 * <p>This is the shared answer, and only the answer. Neither layer's state
 * lives here: the transition is computed from the observation and the caller's
 * own current visit, and what to do about it is the caller's.</p>
 *
 * @param kind         whether the visit begins, continues or ends
 * @param systemAddress the system the visit is of, or null when the record does
 *                     not say
 * @param arrivalBody  the body the visit arrived at, and only for an arrival
 *                     that really happened. Null for a restored session: it
 *                     names the body the ship is sitting at, which may have been
 *                     reached an hour ago, so no arrival-star milestone can be
 *                     claimed for it
 * @param reason       which boundary this is, for the caller that needs to tell
 *                     an arrival from a restore
 */
public record SystemVisitTransition(
        Kind kind,
        Long systemAddress,
        BodyIdentity arrivalBody,
        Reason reason
) {

    public SystemVisitTransition {
        kind = Objects.requireNonNull(kind, "kind");
        reason = Objects.requireNonNull(reason, "reason");
        if (arrivalBody != null && kind != Kind.BEGIN) {
            throw new IllegalArgumentException(
                    "only a beginning visit arrives anywhere"
            );
        }
    }

    /** Whether this observation opens a new visit. */
    public boolean begins() {
        return kind == Kind.BEGIN;
    }

    /** Whether this observation closes the visit in progress. */
    public boolean ends() {
        return kind == Kind.END;
    }

    /** Whether the record is a completed hyperspace arrival. */
    public boolean arrival() {
        return reason == Reason.HYPERSPACE_ARRIVAL;
    }

    /**
     * Whether the record is a session restore, however it was answered.
     *
     * <p>All three restore answers, because they are one kind of record read
     * three ways: whether it opens a visit, restates the one in progress or has
     * to wait for an identity is what the caller then asks. A caller deciding
     * "is this a restore at all" must not have to enumerate them.</p>
     */
    public boolean restore() {
        return reason == Reason.SESSION_RESTORED
                || reason == Reason.LOCATION_RESTATED
                || reason == Reason.IDENTITY_PENDING;
    }

    /** Whether the record is the game session ending. */
    public boolean sessionEnd() {
        return reason == Reason.SESSION_ENDED;
    }

    /**
     * Whether the record says where the ship is, rather than what it did.
     *
     * <p>An arrival and a restore both establish a system outright. Everything
     * else is read against the system already established.</p>
     */
    public boolean statesWhereTheShipIs() {
        return arrival() || restore();
    }

    /** What happens to the visit. */
    public enum Kind {

        /** A new visit starts here; whatever the last one remembered is over. */
        BEGIN,

        /** Nothing about the visit changes. */
        CONTINUE,

        /** The visit in progress is over and no new one starts. */
        END
    }

    /** Which boundary, in the terms the game presents it in. */
    public enum Reason {

        /** A completed hyperspace jump. The one arrival that is an arrival. */
        HYPERSPACE_ARRIVAL,

        /**
         * The Commander or the ship changed.
         *
         * <p>A different vessel is a different behaviour graph and a different
         * run of findings, whether or not the system changed.</p>
         */
        VESSEL_CHANGED,

        /**
         * A session was restored somewhere the visit in progress is not.
         *
         * <p>The Commander is already here and nothing happened; what makes it
         * a boundary is that the previous visit's memory does not apply.</p>
         */
        SESSION_RESTORED,

        /** A restore naming the system already in progress. Not a boundary. */
        LOCATION_RESTATED,

        /**
         * A restore that arrived before the Commander and ship were known.
         *
         * <p>Deferred rather than acted on: a visit opened without an identity
         * belongs to no graph and would be reopened the moment the identity
         * arrived.</p>
         */
        IDENTITY_PENDING,

        /** The game session ended. */
        SESSION_ENDED,

        /** The replay ran out of records. */
        REPLAY_COMPLETED,

        /** The observation source was closed. */
        SOURCE_CLOSED,

        /** Something happened that is not about the visit at all. */
        ORDINARY_EVENT
    }
}
