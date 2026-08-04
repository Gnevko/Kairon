package kairon.behavior.context;

import kairon.behavior.model.ContextKey;
import kairon.behavior.model.ContextSnapshot;
import kairon.semantics.AuxiliaryVehicleTypes;
import kairon.state.CurrentGameStateSnapshot;
import kairon.behavior.normalize.NormalizedEventType;

import java.util.Locale;
import java.util.Objects;

/**
 * Produces deliberately low-cardinality canonical keys for edge counters.
 */
public final class TransitionContextKeyFactory {

    public ContextKey create(
            NormalizedEventType fromEventType,
            ContextSnapshot sourceContext
    ) {
        Objects.requireNonNull(fromEventType, "fromEventType");
        Objects.requireNonNull(sourceContext, "sourceContext");

        if (fromEventType.equals(
                NormalizedEventType.SAA_SIGNALS_FOUND
        )) {
            return new ContextKey(
                    "bioSignals="
                            + biologicalSignalBucket(
                                    sourceContext.biologicalSignalCount()
                            )
                            + "|landable="
                            + triState(sourceContext.landable())
            );
        }
        if (fromEventType.equals(NormalizedEventType.TOUCHDOWN)) {
            return new ContextKey(
                    "vehicle="
                            + vehicleBucket(sourceContext.vehicleKind())
                            + "|bodyHasBiology="
                            + triState(sourceContext.bodyHasBiology())
            );
        }
        return ContextKey.EMPTY;
    }

    private static String biologicalSignalBucket(Integer value) {
        if (value == null || value < 0) {
            return "UNKNOWN";
        }
        return value >= 8 ? "8_PLUS" : Integer.toString(value);
    }

    private static String triState(Boolean value) {
        return value == null
                ? "unknown"
                : value.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * The vehicle class this key counts under.
     *
     * <p>An occurrence restored from a graph written before {@code SLV} existed
     * still carries {@code NOMAD}, which was the class of a Nomad then and is
     * its model now. It is read as the class it always described, so a landing
     * remembered from that graph counts in the same bucket as one recorded
     * today rather than in a bucket nothing will ever match again.</p>
     */
    private static String vehicleBucket(String value) {
        if (value == null) {
            return CurrentGameStateSnapshot.VEHICLE_UNKNOWN;
        }
        String normalized = AuxiliaryVehicleTypes
                .canonicalKind(value.strip().toUpperCase(Locale.ROOT));
        return switch (normalized) {
            case CurrentGameStateSnapshot.VEHICLE_SLV,
                    CurrentGameStateSnapshot.VEHICLE_SRV,
                    CurrentGameStateSnapshot.VEHICLE_SHIP -> normalized;
            default -> CurrentGameStateSnapshot.VEHICLE_UNKNOWN;
        };
    }
}
