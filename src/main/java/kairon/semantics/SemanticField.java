package kairon.semantics;

import java.util.Objects;

/**
 * The canonical fields a fact can be about, each bound to the subject that owns
 * it.
 *
 * <p>One identity per fact, whatever states it. That is what lets an event, a
 * change and a context group be recognised as saying the same thing under the
 * same name, and it is why this enum is not simply a mirror of one record's
 * components. The deprecated flat {@code bodyType} compatibility scalar is
 * deliberately absent: it is a projection of the three independent body
 * dimensions and is not a canonical semantic source.</p>
 *
 * <p>Two sources answer them, and {@link #answeredByCanonicalState} says which
 * is which. {@code kairon.state.CurrentGameStateSnapshot} answers where the
 * Commander is and what is running; the current-system registry answers what a
 * body is like ({@code ADR-0025}). Only the first kind can produce a state
 * delta, because only the first kind is a field of one changing value.</p>
 */
public enum SemanticField {

    COMMANDER_FID(SemanticSubject.COMMANDER, true),

    SHIP_ID(SemanticSubject.PRIMARY_SHIP, true),
    SHIP_TYPE(SemanticSubject.PRIMARY_SHIP, true),
    SHIP_NAME(SemanticSubject.PRIMARY_SHIP, true),
    LOADOUT_HASH(SemanticSubject.PRIMARY_SHIP, true),

    SYSTEM_ADDRESS(SemanticSubject.CURRENT_SYSTEM, true),
    SYSTEM_NAME(SemanticSubject.CURRENT_SYSTEM, true),

    BODY_ID(SemanticSubject.CURRENT_BODY, true),
    BODY_NAME(SemanticSubject.CURRENT_BODY, true),
    BROAD_BODY_TYPE(SemanticSubject.CURRENT_BODY, false),
    PLANET_CLASS(SemanticSubject.CURRENT_BODY, false),
    STAR_TYPE(SemanticSubject.CURRENT_BODY, false),
    LANDABLE(SemanticSubject.CURRENT_BODY, false),
    WAS_DISCOVERED(SemanticSubject.CURRENT_BODY, false),
    WAS_MAPPED(SemanticSubject.CURRENT_BODY, false),
    WAS_FOOTFALLED(SemanticSubject.CURRENT_BODY, false),
    DISTANCE_FROM_ARRIVAL_LS(SemanticSubject.CURRENT_BODY, false),
    BIOLOGICAL_SIGNAL_COUNT(SemanticSubject.CURRENT_BODY, false),
    GEOLOGICAL_SIGNAL_COUNT(SemanticSubject.CURRENT_BODY, false),

    COMMANDER_MODE(SemanticSubject.COMMANDER_PRESENCE, true),

    /**
     * Flight mode.
     *
     * <p>Bound to {@link SemanticSubject#NAVIGATION_CONTEXT} rather than to
     * the ship or the commander: see the Phase B implementation record in
     * {@code docs/design/kairon-llm-situation-v2-design.md}. Every writer is a
     * navigation operation, but no repository evidence establishes whether the
     * value describes the vessel or the commander when the two differ.</p>
     */
    FLIGHT_MODE(SemanticSubject.NAVIGATION_CONTEXT, true),

    VEHICLE_KIND(SemanticSubject.ASSOCIATED_VEHICLE, true),
    ACTIVE_VEHICLE_ID(SemanticSubject.ASSOCIATED_VEHICLE, true),

    /**
     * Whether anything grows on the body.
     *
     * <p>Filed under the sampling subject and answered by the registry, like
     * every other body fact. It has no model-facing name of its own — the
     * biological count beside it says the same thing and says how many.</p>
     */
    BODY_HAS_BIOLOGY(SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS, false),
    ACTIVE_ORGANIC_SAMPLING(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            true
    ),

    /**
     * The active sampling sequence.
     *
     * <p>Body identity is the pair {@code (systemAddress, bodyId)}; a body id
     * alone repeats across systems. Each taxon appears twice: the raw
     * identifier that carries identity, and the game's localised label, which
     * is display only and never an identity key.</p>
     */
    ORGANIC_SAMPLING_SYSTEM_ADDRESS(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            true
    ),
    ORGANIC_SAMPLING_BODY_ID(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            true
    ),
    ORGANIC_SAMPLING_GENUS(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            true
    ),
    ORGANIC_SAMPLING_GENUS_LABEL(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            true
    ),
    ORGANIC_SAMPLING_SPECIES(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            true
    ),
    ORGANIC_SAMPLING_SPECIES_LABEL(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            true
    ),
    ORGANIC_SAMPLING_VARIANT(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            true
    ),
    ORGANIC_SAMPLING_VARIANT_LABEL(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            true
    ),
    ORGANIC_SAMPLING_STAGE(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            true
    );

    private final SemanticSubject subject;
    private final boolean answeredByCanonicalState;

    SemanticField(
            SemanticSubject subject,
            boolean answeredByCanonicalState
    ) {
        this.subject = Objects.requireNonNull(subject, "subject");
        this.answeredByCanonicalState = answeredByCanonicalState;
    }

    public SemanticSubject subject() {
        return subject;
    }

    /**
     * Whether canonical state establishes this field.
     *
     * <p>False for what a body <em>is</em>: the current-system registry holds
     * that, and a body fact is therefore never a canonical delta. Holding them
     * both ways is what forced a write-path flag saying which body changes were
     * the world moving and which were a different body being looked at.</p>
     */
    public boolean answeredByCanonicalState() {
        return answeredByCanonicalState;
    }
}
