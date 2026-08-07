package kairon.observer.decision;

import kairon.semantics.SemanticField;

import java.util.Map;
import java.util.Set;

/**
 * The game mechanism a journal event belongs to.
 *
 * <p>Projection is organised by mechanism rather than by event, so a hundred
 * event types share a dozen ways of being read. A mechanism is a standing claim
 * about the game: what family of thing this event is, and which canonical fields
 * an event of that family states itself.</p>
 *
 * <p>Two of those, and they are different claims. {@code causedFields} are the
 * fields an event of this family <em>moves</em>: a supercruise entry changes the
 * flight mode, and saying so again as a change is the same sentence twice; a
 * disembark changes where the Commander is, and the event kind is that fact.
 * The change is dropped and the current value is still worth stating, because
 * the event says what it <em>did</em> rather than what is now true — a completed
 * jump leaves the ship in supercruise and its sentence never mentions the mode.
 * Where a sentence does name the result, that is a fact about the wording of one
 * class and is declared there: {@link DecisionEventRule#statedValues()}, read
 * against the value, so the context stops repeating it.</p>
 *
 * <p>{@code alsoAnswered} pairs one of the event's own fields with a second
 * canonical field it answers. A completed jump names the system it arrived in,
 * and the arrival star is named after its system — so the jump's {@code system}
 * field is also the body's name, and repeating it as {@code context.body.name}
 * is one string twice. The pairing is declared, by field, and read against the
 * value: a jump whose canonical body is still the previous system's moon says
 * nothing about that moon, and the context still reports it. This used to be
 * caught by comparing every rendered string in the request against every other,
 * which worked for text and silently did not for a boolean or a number.</p>
 *
 * <h2>What is not here</h2>
 * <p>How much of the current situation travels with an event is
 * {@link DecisionContextProfile}, and each mechanism only names its default.
 * The two were one enum, and the cost was two mechanisms that existed to remove
 * body context rather than to name a family of game event — a codex entry and an
 * arrival in an undiscovered system are both exploration, and both simply read a
 * narrower slice. A rule may now say so directly, so narrowing one event's scope
 * is a claim on that event rather than a new constant here.</p>
 *
 * <p>Per-event claims belong on {@link DecisionEventRule}, which is where a
 * later addition — an explanation of what a kind means, for instance — attaches
 * without this enum growing a case for it.</p>
 */
public enum DecisionMechanism {

    /** Commander identity and account milestones. */
    IDENTITY(DecisionContextProfile.NOTHING, Set.of(), Map.of()),

    /** Messages, friends, crew, squadron and wing. */
    SOCIAL(DecisionContextProfile.NOTHING, Set.of(), Map.of()),

    /**
     * Movement between systems and in and out of supercruise.
     *
     * <p>States the body name as well as the flight mode. Completing a jump
     * drops the ship at the arrival star, so the selected body becoming that
     * star is what arriving means rather than something that happened to a
     * body — and the star is named after its system, so reporting it as a
     * change reads as a body being created or renamed after the system. The
     * jump already says which system it arrived in.</p>
     *
     * <p>Narrow in practice: of the events read by this mechanism, only a jump
     * selects a body at all. A supercruise entry clears it, and entering a
     * signal source or boosting off a jet cone touches no body. Arriving at a
     * real body is {@link #BODY_TRANSIT}, which states nothing of the kind.</p>
     */
    TRAVEL(
            DecisionContextProfile.SYSTEM_AND_NAMED_BODY,
            Set.of(SemanticField.FLIGHT_MODE),
            Map.of("system", SemanticField.BODY_NAME)
    ),

    /**
     * Arriving at or departing from a specific body.
     *
     * <p>Separated from travel because what makes an arrival worth remarking on
     * is what is already known about the body — whether anyone has landed on it,
     * whether it carries biological signals — and none of that matters while
     * crossing a system.</p>
     */
    BODY_TRANSIT(
            DecisionContextProfile.SYSTEM_AND_BODY_DETAIL,
            Set.of(SemanticField.FLIGHT_MODE),
            Map.of()
    ),

    /** Landing on and lifting off a surface. */
    SURFACE(
            DecisionContextProfile.SURFACE,
            Set.of(SemanticField.FLIGHT_MODE),
            Map.of()
    ),

    /** Where the Commander physically is: embark, disembark, dropship. */
    PRESENCE(
            DecisionContextProfile.PRESENCE,
            Set.of(SemanticField.COMMANDER_MODE, SemanticField.VEHICLE_KIND),
            Map.of()
    ),

    /** Fighters and SRVs launched, recovered or destroyed. */
    VEHICLE(
            DecisionContextProfile.VEHICLE,
            Set.of(SemanticField.VEHICLE_KIND, SemanticField.ACTIVE_VEHICLE_ID),
            Map.of()
    ),

    /** Docking requests, grants, refusals and the dock itself. */
    DOCKING(
            DecisionContextProfile.SYSTEM_AND_NAVIGATION,
            Set.of(SemanticField.FLIGHT_MODE),
            Map.of()
    ),

    /**
     * Surveys, scans, catalogue entries and the sale of exploration data.
     *
     * <p>The default profile is the system and what is known about the body,
     * because most of these events name the body they are about and are read
     * against it. Two of them are not, and both say so on their own rule rather
     * than by being a family of their own. A codex entry carries a
     * {@code BodyID} the journal itself contradicts — a gas giant and a T Tauri
     * star both filed under body 0 of systems whose body 0 was scanned as a K
     * star and a B star — so the identity cannot be proven and must not be
     * assumed; asking for the body anyway attached the arrival star's type,
     * distance and discovery flags to an entry about a different star. And
     * arriving in a system nobody had discovered is about the system: what it
     * says about the arrival star is the whole of the turn, and the star's
     * survey flags, its distance of zero from the arrival point and a coarse
     * type of {@code STAR} beside its class made a two-sentence turn out of a
     * one-sentence fact.</p>
     */
    EXPLORATION(DecisionContextProfile.SYSTEM_AND_BODY, Set.of(), Map.of()),

    /** The organic sampling sequence. */
    SAMPLING(
            DecisionContextProfile.SAMPLING,
            Set.of(SemanticField.ACTIVE_ORGANIC_SAMPLING),
            Map.of()
    ),

    /** Missions and community goals. */
    MISSION(DecisionContextProfile.SYSTEM_ONLY, Set.of(), Map.of()),

    /** Combat, crime, interdiction and death. */
    COMBAT(
            DecisionContextProfile.SYSTEM_BODY_AND_SHIP,
            Set.of(),
            Map.of()
    ),

    /** Buying, selling, cargo and vouchers. */
    COMMERCE(DecisionContextProfile.SYSTEM_AND_SHIP, Set.of(), Map.of()),

    /** Engineering, technology brokers and on-foot upgrades. */
    ENGINEERING(DecisionContextProfile.SYSTEM_ONLY, Set.of(), Map.of()),

    /** Fleet carrier ownership and jumps. */
    CARRIER(DecisionContextProfile.SYSTEM_ONLY, Set.of(), Map.of()),

    /** System claims and construction. */
    COLONISATION(DecisionContextProfile.SYSTEM_ONLY, Set.of(), Map.of()),

    /** Power allegiance and rank. */
    POWERPLAY(DecisionContextProfile.NOTHING, Set.of(), Map.of()),

    /** Hull, heat, reboot and shutdown. */
    SHIP_STATUS(
            DecisionContextProfile.SHIP_AND_NAVIGATION,
            Set.of(),
            Map.of()
    );

    private final DecisionContextProfile contextProfile;
    private final Set<SemanticField> causedFields;
    private final Map<String, SemanticField> alsoAnswered;

    DecisionMechanism(
            DecisionContextProfile contextProfile,
            Set<SemanticField> causedFields,
            Map<String, SemanticField> alsoAnswered
    ) {
        this.contextProfile = contextProfile;
        this.causedFields = Set.copyOf(causedFields);
        this.alsoAnswered = Map.copyOf(alsoAnswered);
    }

    /**
     * How much of the situation an event of this family needs by default.
     *
     * <p>A default, not a property: a rule that names a profile overrides it,
     * and {@link DecisionEventRule#contextProfile()} is what the projection
     * actually reads.</p>
     */
    public DecisionContextProfile contextProfile() {
        return contextProfile;
    }

    /**
     * Whether an event of this mechanism already states a canonical field,
     * either way.
     *
     * <p>What a change is checked against: a field the event moved and a field
     * the event already says are both fields whose change the event has
     * covered.</p>
     */
    public boolean states(SemanticField field) {
        return causedFields.contains(field)
                || alsoAnswered.containsValue(field);
    }

    /**
     * The second canonical field one of this event's own fields answers.
     *
     * <p>Keyed by the event field's model-facing name, so the pairing is
     * declared rather than discovered by two values happening to be equal. Read
     * against the value where it is used, which is the difference from
     * {@link #states}: a jump moves the flight mode whatever the mode becomes,
     * but it only says the body's name when the body is the star it arrived
     * at.</p>
     */
    public SemanticField alsoAnsweredBy(String eventFieldName) {
        return alsoAnswered.get(eventFieldName);
    }
}
