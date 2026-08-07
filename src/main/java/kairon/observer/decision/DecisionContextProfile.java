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
            ContextNeed.BODY_GRAVITY,
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
     * Where the Commander physically is, and nothing about what they are doing.
     *
     * <p>A running sampling sequence used to be asked for here, on the argument
     * that getting out and getting back in are exactly the moves a Commander
     * makes in the middle of one. The argument is true and the result was
     * wrong: the sequence is more interesting than the step, so the model spoke
     * about the sequence every time and about the event never.</p>
     *
     * <p><strong>Measured across the live session of 2026-08-07:</strong> nine
     * turns of stepping out and getting back in produced eight comments, and
     * all eight were about collecting samples. Three announced a find that had
     * happened turns earlier ("a new organism has been found"), one placed a
     * sample aboard that was not collected yet, and none said what the turn was
     * actually about. On the one turn that <em>was</em> a step of the sequence,
     * she stayed silent — the standing fact had already been used up as
     * news.</p>
     *
     * <p>The sequence still reports itself on its own steps, where
     * {@link #SAMPLING} and {@link #SAMPLING_ANALYSED} ask for it. Absent here
     * means the sampling subject is also out of scope for a hidden change,
     * which is the same claim read the other way: a disembark is not about
     * sampling.</p>
     */
    PRESENCE(Set.of(
            ContextNeed.PRESENCE,
            ContextNeed.VEHICLE,
            ContextNeed.BODY_IDENTITY
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
     * The body, where the Commander is, and the sequence as a subject.
     *
     * <p>{@link ContextNeed#SAMPLING} builds no group; it puts the sampling
     * subject in scope so a change a hidden observation made can be reported
     * here. The sequence itself is described by the scan that steps it.</p>
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
                case BODY_IDENTITY, BODY_DETAIL, BODY_GRAVITY -> "body";
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

        /**
         * How heavily it pulls — asked for by an arrival and by nothing else.
         *
         * <p>Weight is a question about the descent: whether the ship can be
         * put down without wrecking it. That question is asked while coming
         * in, and it is over by the time the landing gear is on the ground. A
         * touchdown, a lift-off, a sample and a walk are none of them decided
         * by it — in the live session of 2026-08-07 the same "gravity is low"
         * arrived on three landings of one body, having already arrived on the
         * approach to it.</p>
         *
         * <p>Separate from {@link #BODY_DETAIL} so that this is a decision
         * rather than a side effect of asking what the body is: what it is and
         * how heavily it pulls are wanted at different moments.</p>
         */
        BODY_GRAVITY,

        /** How the ship is travelling. */
        NAVIGATION,

        /** Where the Commander physically is. */
        PRESENCE,

        /** The Commander's own ship. */
        SHIP,

        /** The associated auxiliary vehicle. */
        VEHICLE,

        /**
         * The sampling sequence as a subject, not as a group.
         *
         * <p>It builds nothing. There is no {@code context.sampling}: the
         * sequence is described by the scans that step it — {@code organism},
         * {@code stage}, {@code complete} — and a standing description beside
         * them was the same sequence said twice in two vocabularies. What this
         * need still does is put the subject in scope, so a change to the
         * sequence that a hidden observation made can be reported on a turn
         * whose events are about sampling.</p>
         */
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
