package kairon.behavior.normalize;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.Objects;

/**
 * Extensible canonical event kind. A value object is used instead of an enum
 * so explicitly selected future journal events can retain an UNKNOWN_* kind.
 */
public record NormalizedEventType(@JsonValue String value)
        implements Comparable<NormalizedEventType> {

    public static final NormalizedEventType SYSTEM_ENTRY = of("SYSTEM_ENTRY");
    public static final NormalizedEventType FSS_DISCOVERY_SCAN =
            of("FSS_DISCOVERY_SCAN");
    public static final NormalizedEventType FSS_ALL_BODIES_FOUND =
            of("FSS_ALL_BODIES_FOUND");
    public static final NormalizedEventType FSS_BODY_SIGNALS_FOUND =
            of("FSS_BODY_SIGNALS_FOUND");
    public static final NormalizedEventType BODY_SCANNED = of("BODY_SCANNED");
    public static final NormalizedEventType SYSTEM_UNDISCOVERED_CONFIRMED =
            of("SYSTEM_UNDISCOVERED_CONFIRMED");
    public static final NormalizedEventType SAA_SCAN_COMPLETE =
            of("SAA_SCAN_COMPLETE");
    public static final NormalizedEventType SAA_SIGNALS_FOUND =
            of("SAA_SIGNALS_FOUND");
    public static final NormalizedEventType APPROACH_BODY = of("APPROACH_BODY");
    public static final NormalizedEventType SUPERCRUISE_ENTRY =
            of("SUPERCRUISE_ENTRY");
    public static final NormalizedEventType SUPERCRUISE_EXIT =
            of("SUPERCRUISE_EXIT");
    public static final NormalizedEventType AUXILIARY_VEHICLE_LAUNCHED =
            of("AUXILIARY_VEHICLE_LAUNCHED");
    public static final NormalizedEventType TOUCHDOWN = of("TOUCHDOWN");
    public static final NormalizedEventType DISEMBARK = of("DISEMBARK");
    public static final NormalizedEventType SCAN_ORGANIC_LOG =
            of("SCAN_ORGANIC_LOG");
    public static final NormalizedEventType SCAN_ORGANIC_SAMPLE =
            of("SCAN_ORGANIC_SAMPLE");
    public static final NormalizedEventType SCAN_ORGANIC_ANALYSE =
            of("SCAN_ORGANIC_ANALYSE");
    public static final NormalizedEventType EMBARK = of("EMBARK");
    public static final NormalizedEventType LIFTOFF = of("LIFTOFF");
    public static final NormalizedEventType LEAVE_BODY = of("LEAVE_BODY");
    public static final NormalizedEventType FSS_MODE_ENTERED =
            of("FSS_MODE_ENTERED");
    public static final NormalizedEventType FSS_MODE_EXITED =
            of("FSS_MODE_EXITED");
    public static final NormalizedEventType SAA_MODE_ENTERED =
            of("SAA_MODE_ENTERED");
    public static final NormalizedEventType SAA_MODE_EXITED =
            of("SAA_MODE_EXITED");
    public static final NormalizedEventType LANDING_GEAR_DEPLOYED =
            of("LANDING_GEAR_DEPLOYED");
    public static final NormalizedEventType LANDING_GEAR_RETRACTED =
            of("LANDING_GEAR_RETRACTED");
    public static final NormalizedEventType AUXILIARY_VEHICLE_DOCKED =
            of("AUXILIARY_VEHICLE_DOCKED");
    public static final NormalizedEventType HYPERSPACE_JUMP_STARTED =
            of("HYPERSPACE_JUMP_STARTED");
    public static final NormalizedEventType SUPERCRUISE_JUMP_STARTED =
            of("SUPERCRUISE_JUMP_STARTED");
    public static final NormalizedEventType FSD_TARGET_SELECTED =
            of("FSD_TARGET_SELECTED");
    public static final NormalizedEventType DOCKING_REQUESTED =
            of("DOCKING_REQUESTED");
    public static final NormalizedEventType DOCKING_GRANTED =
            of("DOCKING_GRANTED");
    public static final NormalizedEventType DOCKED = of("DOCKED");
    public static final NormalizedEventType UNDOCKED = of("UNDOCKED");
    public static final NormalizedEventType LIMPET_LAUNCHED =
            of("LIMPET_LAUNCHED");
    public static final NormalizedEventType HATCH_BREAKER_LIMPET_LAUNCHED =
            of("HATCH_BREAKER_LIMPET_LAUNCHED");
    public static final NormalizedEventType FUEL_TRANSFER_LIMPET_LAUNCHED =
            of("FUEL_TRANSFER_LIMPET_LAUNCHED");
    public static final NormalizedEventType COLLECTION_LIMPET_LAUNCHED =
            of("COLLECTION_LIMPET_LAUNCHED");
    public static final NormalizedEventType PROSPECTOR_LIMPET_LAUNCHED =
            of("PROSPECTOR_LIMPET_LAUNCHED");
    public static final NormalizedEventType REPAIR_LIMPET_LAUNCHED =
            of("REPAIR_LIMPET_LAUNCHED");
    public static final NormalizedEventType RESEARCH_LIMPET_LAUNCHED =
            of("RESEARCH_LIMPET_LAUNCHED");
    public static final NormalizedEventType
            DECONTAMINATION_LIMPET_LAUNCHED =
            of("DECONTAMINATION_LIMPET_LAUNCHED");
    public static final NormalizedEventType RECON_LIMPET_LAUNCHED =
            of("RECON_LIMPET_LAUNCHED");
    public static final NormalizedEventType INTERDICTED = of("INTERDICTED");
    public static final NormalizedEventType UNDER_ATTACK = of("UNDER_ATTACK");
    public static final NormalizedEventType MATERIAL_COLLECTED =
            of("MATERIAL_COLLECTED");
    public static final NormalizedEventType FUEL_SCOOPING =
            of("FUEL_SCOOPING");
    public static final NormalizedEventType MARKET_BUY = of("MARKET_BUY");
    public static final NormalizedEventType MARKET_SELL = of("MARKET_SELL");
    public static final NormalizedEventType REDEEM_VOUCHER =
            of("REDEEM_VOUCHER");
    public static final NormalizedEventType MISSION_ACCEPTED =
            of("MISSION_ACCEPTED");
    public static final NormalizedEventType MISSION_COMPLETED =
            of("MISSION_COMPLETED");
    public static final NormalizedEventType MISSION_FAILED =
            of("MISSION_FAILED");
    public static final NormalizedEventType MISSION_ABANDONED =
            of("MISSION_ABANDONED");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public NormalizedEventType {
        value = Objects.requireNonNull(value, "value");
        if (value.isBlank()
                || !value.equals(value.toUpperCase(Locale.ROOT))
                || !value.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(
                    "normalized event type must be canonical upper snake case"
            );
        }
    }

    public static NormalizedEventType of(String value) {
        return new NormalizedEventType(value);
    }

    public static NormalizedEventType unknown(String originalEventName) {
        Objects.requireNonNull(originalEventName, "originalEventName");
        String normalized = originalEventName
                .strip()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
        return of("UNKNOWN_" + (normalized.isEmpty() ? "EVENT" : normalized));
    }

    @Override
    public int compareTo(NormalizedEventType other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
