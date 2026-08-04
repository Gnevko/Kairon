package kairon.semantics;

import java.util.Objects;

/**
 * The canonical state fields a semantic state change can describe, each bound
 * to the subject that owns it.
 *
 * <p>These mirror the components of
 * {@code kairon.state.CurrentGameStateSnapshot}. The deprecated flat
 * {@code bodyType} compatibility scalar is deliberately absent: it is a
 * projection of the three independent body dimensions and is not a canonical
 * semantic source.</p>
 */
public enum SemanticField {

    COMMANDER_FID(SemanticSubject.COMMANDER, false),

    SHIP_ID(SemanticSubject.PRIMARY_SHIP, false),
    SHIP_TYPE(SemanticSubject.PRIMARY_SHIP, false),
    SHIP_NAME(SemanticSubject.PRIMARY_SHIP, false),
    LOADOUT_HASH(SemanticSubject.PRIMARY_SHIP, false),

    SYSTEM_ADDRESS(SemanticSubject.CURRENT_SYSTEM, false),
    SYSTEM_NAME(SemanticSubject.CURRENT_SYSTEM, false),

    BODY_ID(SemanticSubject.CURRENT_BODY, false),
    BODY_NAME(SemanticSubject.CURRENT_BODY, true),
    BROAD_BODY_TYPE(SemanticSubject.CURRENT_BODY, false),
    PLANET_CLASS(SemanticSubject.CURRENT_BODY, true),
    STAR_TYPE(SemanticSubject.CURRENT_BODY, true),
    LANDABLE(SemanticSubject.CURRENT_BODY, true),
    WAS_DISCOVERED(SemanticSubject.CURRENT_BODY, true),
    WAS_MAPPED(SemanticSubject.CURRENT_BODY, true),
    WAS_FOOTFALLED(SemanticSubject.CURRENT_BODY, true),
    DISTANCE_FROM_ARRIVAL_LS(SemanticSubject.CURRENT_BODY, true),
    BIOLOGICAL_SIGNAL_COUNT(SemanticSubject.CURRENT_BODY, true),
    GEOLOGICAL_SIGNAL_COUNT(SemanticSubject.CURRENT_BODY, true),

    COMMANDER_MODE(SemanticSubject.COMMANDER_PRESENCE, false),

    /**
     * Flight mode.
     *
     * <p>Bound to {@link SemanticSubject#NAVIGATION_CONTEXT} rather than to
     * the ship or the commander: see the Phase B implementation record in
     * {@code docs/design/kairon-llm-situation-v2-design.md}. Every writer is a
     * navigation operation, but no repository evidence establishes whether the
     * value describes the vessel or the commander when the two differ.</p>
     */
    FLIGHT_MODE(SemanticSubject.NAVIGATION_CONTEXT, false),

    VEHICLE_KIND(SemanticSubject.ASSOCIATED_VEHICLE, false),
    ACTIVE_VEHICLE_ID(SemanticSubject.ASSOCIATED_VEHICLE, false),

    BODY_HAS_BIOLOGY(SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS, true),
    ACTIVE_ORGANIC_SAMPLING(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            false
    ),

    /**
     * The active sampling sequence.
     *
     * <p>Body identity is the pair {@code (systemAddress, bodyId)}; a body id
     * alone repeats across systems. Each taxon appears twice: the raw
     * identifier that carries identity, and the game's localised label, which
     * is display only and never an identity key.</p>
     *
     * <p>None is body-registry derived. The projector cannot serve a sampling
     * sequence from stored per-body context, so none can ever be
     * {@link SemanticChangeKind#ACTIVATED_FROM_CONTEXT}.</p>
     */
    ORGANIC_SAMPLING_SYSTEM_ADDRESS(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            false
    ),
    ORGANIC_SAMPLING_BODY_ID(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            false
    ),
    ORGANIC_SAMPLING_GENUS(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            false
    ),
    ORGANIC_SAMPLING_GENUS_LABEL(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            false
    ),
    ORGANIC_SAMPLING_SPECIES(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            false
    ),
    ORGANIC_SAMPLING_SPECIES_LABEL(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            false
    ),
    ORGANIC_SAMPLING_VARIANT(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            false
    ),
    ORGANIC_SAMPLING_VARIANT_LABEL(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            false
    ),
    ORGANIC_SAMPLING_STAGE(
            SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
            false
    );

    private final SemanticSubject subject;
    private final boolean bodyRegistryDerived;

    SemanticField(SemanticSubject subject, boolean bodyRegistryDerived) {
        this.subject = Objects.requireNonNull(subject, "subject");
        this.bodyRegistryDerived = bodyRegistryDerived;
    }

    public SemanticSubject subject() {
        return subject;
    }

    /**
     * Whether the projector can serve this field from its stored per-body
     * registry rather than from the current observation.
     *
     * <p>Only these fields can ever be
     * {@link SemanticChangeKind#ACTIVATED_FROM_CONTEXT}.</p>
     */
    public boolean bodyRegistryDerived() {
        return bodyRegistryDerived;
    }
}
