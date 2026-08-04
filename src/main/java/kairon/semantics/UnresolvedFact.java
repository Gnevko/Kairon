package kairon.semantics;

import java.util.Objects;

/**
 * An explicitly recorded gap.
 *
 * <p>Kairon states what it does not know rather than guessing or silently
 * omitting it. Taxi, multicrew and fighter relationships are the standing
 * cases.</p>
 */
public record UnresolvedFact(
        SemanticSubject subject,
        Reason reason,
        SemanticProvenance provenance
) {

    public UnresolvedFact {
        subject = Objects.requireNonNull(subject, "subject");
        reason = Objects.requireNonNull(reason, "reason");
        provenance = Objects.requireNonNull(provenance, "provenance");
    }

    /** Why a fact could not be established. Closed and non-speculative. */
    public enum Reason {

        /** No semantic adapter is registered for this observation type. */
        NO_SEMANTIC_ADAPTER,

        /**
         * The commander may be in a taxi or dropship; the canonical projection
         * does not model it, so the vessel relationship is not established.
         */
        TAXI_CONTEXT_NOT_MODELLED,

        /**
         * The observation reports multicrew participation; the canonical
         * projection does not model it.
         */
        MULTICREW_CONTEXT_NOT_MODELLED,

        /**
         * A fighter was launched. Controlling a fighter does not establish
         * where the commander physically is, and the projection keeps one
         * vehicle slot, so occupancy is not established.
         */
        FIGHTER_OCCUPANCY_NOT_ESTABLISHED,

        /**
         * An auxiliary vehicle is associated with the commander, but the
         * projection cannot say whether it is occupied.
         */
        VEHICLE_OCCUPANCY_NOT_ESTABLISHED,

        /**
         * A friend was reported {@code Online}, which the game also emits at
         * startup for friends who were already online. The event alone does
         * not establish that a login happened now.
         */
        LOGIN_TRANSITION_NOT_ESTABLISHED,

        /** An identifier was present but its entity kind is not derivable. */
        IDENTIFIER_KIND_NOT_ESTABLISHED,

        /**
         * The event is model-eligible, but part of its critical semantics
         * cannot be established from the repository or from authoritative
         * evidence, so it is deliberately left unbuilt rather than guessed.
         */
        AUTHORITATIVE_SEMANTICS_NOT_ESTABLISHED
    }
}
