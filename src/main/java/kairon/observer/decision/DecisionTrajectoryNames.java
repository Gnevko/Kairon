package kairon.observer.decision;

import kairon.behavior.normalize.NormalizedEventType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a remembered or predicted event is called in domain terms.
 *
 * <p>The trajectory is the one place a name reaches the model without a journal
 * payload behind it: a predecessor is remembered as a normalized type, and a
 * prediction is only ever a type. Neither can go through
 * {@link DecisionEventCatalog}, which is keyed by journal class — so this is a
 * second table, and it exists to say the same words the events say.</p>
 *
 * <p>Where a normalized type corresponds to a catalogued journal event, the
 * name here <strong>is</strong> that event's kind, and a test asserts the two
 * tables agree on every such type. The rest are types no journal event the
 * model is eligible to see produces: Status-derived scanner modes and landing
 * gear, limpets, the episode root. Those are named here in the same register
 * rather than left to leak a normalized spelling.</p>
 *
 * <p>Three types are named more finely than the catalogue names them. Organic
 * sampling is one catalogued kind carrying a {@code stage}, and the trajectory
 * has no stage — so the position is folded into the name, which is the only way
 * a remembered {@code BIOLOGICAL_SAMPLE} can say whether it was the first scan
 * or the last.</p>
 *
 * <p>An unmapped type yields {@code null}. That is a real case: an unrecognised
 * scan type or jump type normalizes to an {@code UNKNOWN_*} value built from the
 * journal's own event name, and passing that through would put a Frontier
 * identifier in front of the model. The occurrence is dropped from the
 * trajectory instead.</p>
 */
final class DecisionTrajectoryNames {

    private static final Map<NormalizedEventType, String> NAMES = build();

    private DecisionTrajectoryNames() {
    }

    /** The domain name for a normalized type, or null if it has none. */
    static String kindOf(NormalizedEventType eventType) {
        return NAMES.get(Objects.requireNonNull(eventType, "eventType"));
    }

    /** Every normalized type this vocabulary covers. */
    static Map<NormalizedEventType, String> names() {
        return NAMES;
    }

    private static Map<NormalizedEventType, String> build() {
        Map<NormalizedEventType, String> names = new LinkedHashMap<>();

        // The episode root: arriving in the system this trajectory covers.
        names.put(NormalizedEventType.SYSTEM_ENTRY, "SYSTEM_ENTERED");
        names.put(NormalizedEventType.HYPERSPACE_JUMP_STARTED,
                "SYSTEM_JUMP_STARTED");
        names.put(NormalizedEventType.SUPERCRUISE_JUMP_STARTED,
                "SUPERCRUISE_JUMP_STARTED");
        names.put(NormalizedEventType.FSD_TARGET_SELECTED,
                "ROUTE_TARGET_SELECTED");
        names.put(NormalizedEventType.SUPERCRUISE_ENTRY,
                "SUPERCRUISE_ENTERED");
        names.put(NormalizedEventType.SUPERCRUISE_EXIT, "SUPERCRUISE_EXITED");
        names.put(NormalizedEventType.APPROACH_BODY, "BODY_APPROACHED");
        names.put(NormalizedEventType.LEAVE_BODY, "BODY_LEFT");

        names.put(NormalizedEventType.TOUCHDOWN, "TOUCHDOWN");
        names.put(NormalizedEventType.LIFTOFF, "LIFTOFF");

        names.put(NormalizedEventType.DISEMBARK, "DISEMBARKED");
        names.put(NormalizedEventType.EMBARK, "EMBARKED");

        names.put(NormalizedEventType.AUXILIARY_VEHICLE_LAUNCHED,
                "VEHICLE_LAUNCHED");
        names.put(NormalizedEventType.AUXILIARY_VEHICLE_DOCKED,
                "VEHICLE_RECOVERED");

        names.put(NormalizedEventType.FSS_DISCOVERY_SCAN, "SYSTEM_SCANNED");
        names.put(NormalizedEventType.FSS_ALL_BODIES_FOUND,
                "SYSTEM_SURVEY_COMPLETED");
        names.put(NormalizedEventType.BODY_SCANNED, "BODY_SCANNED");
        // The arrival-star milestone. Named as the catalogue names it, because
        // the same reading reaches the model as an event under that kind.
        names.put(NormalizedEventType.SYSTEM_UNDISCOVERED_CONFIRMED,
                "SYSTEM_UNDISCOVERED_CONFIRMED");
        // Two structural types, one remembered fact. Which scanner reported
        // the signals is Kairon's bookkeeping; a reading is only recorded once
        // per body per visit, so the shared name can never say the same thing
        // twice.
        names.put(NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                "BODY_SIGNALS_FOUND");
        names.put(NormalizedEventType.SAA_SIGNALS_FOUND, "BODY_SIGNALS_FOUND");
        names.put(NormalizedEventType.SAA_SCAN_COMPLETE,
                "BODY_MAPPING_COMPLETED");

        // One catalogued kind plus a stage, said as three names, because a
        // remembered event carries no stage of its own.
        names.put(NormalizedEventType.SCAN_ORGANIC_LOG,
                "BIOLOGICAL_SAMPLE_STARTED");
        names.put(NormalizedEventType.SCAN_ORGANIC_SAMPLE,
                "BIOLOGICAL_SAMPLE_CONTINUED");
        names.put(NormalizedEventType.SCAN_ORGANIC_ANALYSE,
                "BIOLOGICAL_SAMPLE_COMPLETED");

        // Status-derived. No journal event produces these, so no catalogued
        // kind exists to borrow; they are named for what the Commander did.
        names.put(NormalizedEventType.FSS_MODE_ENTERED,
                "SYSTEM_SCANNER_OPENED");
        names.put(NormalizedEventType.FSS_MODE_EXITED,
                "SYSTEM_SCANNER_CLOSED");
        names.put(NormalizedEventType.SAA_MODE_ENTERED,
                "SURFACE_SCANNER_OPENED");
        names.put(NormalizedEventType.SAA_MODE_EXITED,
                "SURFACE_SCANNER_CLOSED");
        names.put(NormalizedEventType.LANDING_GEAR_DEPLOYED,
                "LANDING_GEAR_DEPLOYED");
        names.put(NormalizedEventType.LANDING_GEAR_RETRACTED,
                "LANDING_GEAR_RETRACTED");

        names.put(NormalizedEventType.DOCKING_REQUESTED, "DOCKING_REQUESTED");
        names.put(NormalizedEventType.DOCKING_GRANTED, "DOCKING_GRANTED");
        names.put(NormalizedEventType.DOCKED, "DOCKED");
        names.put(NormalizedEventType.UNDOCKED, "UNDOCKED");

        names.put(NormalizedEventType.LIMPET_LAUNCHED, "LIMPET_LAUNCHED");
        names.put(NormalizedEventType.HATCH_BREAKER_LIMPET_LAUNCHED,
                "HATCH_BREAKER_LIMPET_LAUNCHED");
        names.put(NormalizedEventType.FUEL_TRANSFER_LIMPET_LAUNCHED,
                "FUEL_TRANSFER_LIMPET_LAUNCHED");
        names.put(NormalizedEventType.COLLECTION_LIMPET_LAUNCHED,
                "COLLECTION_LIMPET_LAUNCHED");
        names.put(NormalizedEventType.PROSPECTOR_LIMPET_LAUNCHED,
                "PROSPECTOR_LIMPET_LAUNCHED");
        names.put(NormalizedEventType.REPAIR_LIMPET_LAUNCHED,
                "REPAIR_LIMPET_LAUNCHED");
        names.put(NormalizedEventType.RESEARCH_LIMPET_LAUNCHED,
                "RESEARCH_LIMPET_LAUNCHED");
        names.put(NormalizedEventType.DECONTAMINATION_LIMPET_LAUNCHED,
                "DECONTAMINATION_LIMPET_LAUNCHED");
        names.put(NormalizedEventType.RECON_LIMPET_LAUNCHED,
                "RECON_LIMPET_LAUNCHED");

        names.put(NormalizedEventType.INTERDICTED, "INTERDICTED");
        names.put(NormalizedEventType.UNDER_ATTACK, "UNDER_ATTACK");

        names.put(NormalizedEventType.MATERIAL_COLLECTED,
                "MATERIAL_COLLECTED");
        names.put(NormalizedEventType.FUEL_SCOOPING, "FUEL_SCOOPED");

        names.put(NormalizedEventType.MARKET_BUY, "COMMODITY_BOUGHT");
        names.put(NormalizedEventType.MARKET_SELL, "COMMODITY_SOLD");
        names.put(NormalizedEventType.REDEEM_VOUCHER, "VOUCHER_REDEEMED");

        names.put(NormalizedEventType.MISSION_ACCEPTED, "MISSION_ACCEPTED");
        names.put(NormalizedEventType.MISSION_COMPLETED, "MISSION_COMPLETED");
        names.put(NormalizedEventType.MISSION_FAILED, "MISSION_FAILED");
        names.put(NormalizedEventType.MISSION_ABANDONED, "MISSION_ABANDONED");

        return Map.copyOf(names);
    }
}
