package kairon.observer.decision;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What an event needs to be read against, and which subjects it has business
 * hearing about.
 *
 * <p>Separate from {@link DecisionMechanism} on purpose. A mechanism is a
 * standing claim about the game — what family of thing this event is, and which
 * canonical fields an event of that family states itself. A context profile is a
 * claim about the request: how much of the current situation has to travel with
 * the event for it to be read correctly. They vary independently, and folding
 * them made the second one impossible to change without inventing a new
 * mechanism.</p>
 *
 * <p>That is exactly what happened twice. A codex entry is exploration, and its
 * body id is contradicted by the journal that emits it — so it must not carry
 * canonical body facts. Arriving in an undiscovered system is exploration too,
 * and what it asserts about the arrival star is the whole turn — so body facts
 * add nothing. Both became mechanisms of their own, which said they were
 * different families of game event. They are not; they read a narrower slice.
 * </p>
 *
 * <p>A profile is chosen per rule where the mechanism's default is wrong, so
 * narrowing one event's scope is a claim on that event rather than a new enum
 * constant everything else then has to be checked against.</p>
 */
public enum DecisionContextProfile {

    /** Nothing about the situation matters. */
    NOTHING(Set.of()),

    /** Which star system, and nothing else. */
    SYSTEM_ONLY(Set.of(ContextNeed.SYSTEM)),

    /** The system, the body by name, and how the ship is travelling. */
    SYSTEM_AND_NAMED_BODY(Set.of(
            ContextNeed.SYSTEM,
            ContextNeed.BODY_IDENTITY,
            ContextNeed.NAVIGATION
    )),

    /**
     * The system, what is known about the body, and how the ship is travelling.
     *
     * <p>What makes an arrival worth remarking on is what is already known about
     * the body — whether anyone has landed on it, whether it carries biological
     * signals — and none of that matters while crossing a system.</p>
     */
    SYSTEM_AND_BODY_DETAIL(Set.of(
            ContextNeed.SYSTEM,
            ContextNeed.BODY_DETAIL,
            ContextNeed.NAVIGATION
    )),

    /**
     * A surface manoeuvre: the body, the system, the flight and the vehicle.
     *
     * <p>Which vehicle is out changes what a landing is. Setting a ship down on
     * a body and driving an SRV onto it are different events, and the record
     * itself says only {@code playerControlled}. Presence is deliberately not
     * asked for: where the Commander is sitting is a separate fact that a
     * landing does not establish.</p>
     */
    SURFACE(Set.of(
            ContextNeed.BODY_DETAIL,
            ContextNeed.NAVIGATION,
            ContextNeed.SYSTEM,
            ContextNeed.VEHICLE
    )),

    /**
     * Where the Commander physically is, and what they were in the middle of.
     *
     * <p>A running sampling sequence is asked for as well. Getting out and
     * getting back in are exactly the moves a Commander makes in the middle of
     * one, and the three remembered predecessors are too short a memory to keep
     * the scan that started it.</p>
     */
    PRESENCE(Set.of(
            ContextNeed.PRESENCE,
            ContextNeed.VEHICLE,
            ContextNeed.BODY_IDENTITY,
            ContextNeed.SAMPLING
    )),

    /** The auxiliary vehicle and where the Commander is. */
    VEHICLE(Set.of(ContextNeed.VEHICLE, ContextNeed.PRESENCE)),

    /** The system and how the ship is travelling. */
    SYSTEM_AND_NAVIGATION(Set.of(
            ContextNeed.SYSTEM,
            ContextNeed.NAVIGATION
    )),

    /** The system and what is known about the body. */
    SYSTEM_AND_BODY(Set.of(ContextNeed.SYSTEM, ContextNeed.BODY_DETAIL)),

    /**
     * The body, the sequence in progress, and where the Commander is.
     *
     * <p>The sampling group it would build is dropped when one of the turn's own
     * events is a step of the sequence — see {@code DecisionContextSelector}.
     * The need is kept because it is also what puts the sampling subject in
     * scope for a hidden change, which a scan does not state and which is
     * dropped by subject rather than by group.</p>
     */
    SAMPLING(Set.of(
            ContextNeed.BODY_DETAIL,
            ContextNeed.SAMPLING,
            ContextNeed.PRESENCE
    )),

    /**
     * The same, plus what grows here and what is already collected.
     *
     * <p>Only the analysis that finishes a sample reads this. The inventory is
     * an answer to one question — what is left to collect on this body — and
     * that question is asked once, when a sample has just been analysed. Every
     * other turn was carrying it as a standing fact, so a landing, an approach
     * and a scan each said "not collected" about an organism nobody was
     * collecting yet.</p>
     */
    SAMPLING_ANALYSED(Set.of(
            ContextNeed.BODY_DETAIL,
            ContextNeed.SAMPLING,
            ContextNeed.PRESENCE,
            ContextNeed.BIOLOGY
    )),

    /** The system, the body by name, and the ship. */
    SYSTEM_BODY_AND_SHIP(Set.of(
            ContextNeed.SYSTEM,
            ContextNeed.BODY_IDENTITY,
            ContextNeed.SHIP
    )),

    /** The system and the ship. */
    SYSTEM_AND_SHIP(Set.of(ContextNeed.SYSTEM, ContextNeed.SHIP)),

    /** The ship and how it is travelling. */
    SHIP_AND_NAVIGATION(Set.of(ContextNeed.SHIP, ContextNeed.NAVIGATION));

    private final Set<ContextNeed> contextNeeds;

    DecisionContextProfile(Set<ContextNeed> contextNeeds) {
        this.contextNeeds = Set.copyOf(contextNeeds);
    }

    /** What this profile may ask the current situation for. */
    public Set<ContextNeed> contextNeeds() {
        return contextNeeds;
    }

    /**
     * Which subjects an event read this way has any business hearing about.
     *
     * <p>Used for hidden changes as well as context. An observation the model
     * is not being shown can still explain a current event — but only if it
     * touched something in scope. A chat message is not clarified by a ship
     * having been loaded a minute earlier.</p>
     */
    public Set<String> subjectsInScope() {
        Set<String> subjects = new LinkedHashSet<>();
        for (ContextNeed need : contextNeeds) {
            subjects.add(switch (need) {
                case SYSTEM -> "system";
                case BODY_IDENTITY, BODY_DETAIL -> "body";
                case NAVIGATION -> "navigation";
                case PRESENCE -> "commander";
                case SHIP -> "ship";
                case VEHICLE -> "vehicle";
                case SAMPLING -> "sampling";
                // What grows on it is a fact about the body, like its class.
                case BIOLOGY -> "body";
            });
        }
        return Set.copyOf(subjects);
    }

    /** Whether an event read this way is about a body at all. */
    public boolean asksAboutABody() {
        return contextNeeds.contains(ContextNeed.BODY_IDENTITY)
                || contextNeeds.contains(ContextNeed.BODY_DETAIL);
    }

    /** What a profile may ask the current situation for. */
    public enum ContextNeed {

        /** Which star system. */
        SYSTEM,

        /** Which body, by name only. */
        BODY_IDENTITY,

        /** The body and what is known about it. */
        BODY_DETAIL,

        /** How the ship is travelling. */
        NAVIGATION,

        /** Where the Commander physically is. */
        PRESENCE,

        /** The Commander's own ship. */
        SHIP,

        /** The associated auxiliary vehicle. */
        VEHICLE,

        /** A running organic sampling sequence. */
        SAMPLING,

        /**
         * What grows on this body and what has been collected of it.
         *
         * <p>Asked for by one event only: the analysis that finishes a sample.
         * That is the moment the inventory answers a question — what is left to
         * collect here — and it was travelling with every landing, approach and
         * scan of the body instead, saying "not collected" beside events that
         * had nothing to do with collecting. A standing fact that is true in
         * every turn is not thereby needed in every turn.</p>
         */
        BIOLOGY
    }
}
