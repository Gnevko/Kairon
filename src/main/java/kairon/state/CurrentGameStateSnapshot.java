package kairon.state;

import kairon.semantics.AuxiliaryVehicleTypes;

import java.util.Objects;

/**
 * Immutable canonical projection of facts established by processed journal
 * events. Nullable fields retain the previous unknown-value semantics.
 *
 * <p>Where the Commander is, in what, and what sequence is running. The body is
 * named and identified, and nothing more about it is here: what a body
 * <em>is</em> — its class, how far out it sits, what a scanner counted on it —
 * belongs to the system it is in and lives in the current-system registry
 * ({@code ADR-0025}).</p>
 *
 * <p>That is a boundary rather than a tidy-up. Held here, a body fact was a
 * field of "the current body", so flying to the next body made every one of
 * them change at once — and Kairon had to carry a write-path flag saying which
 * of those changes were the world moving and which were only a different body
 * being looked at. Nothing states now what nothing has to unstate later.</p>
 */
public record CurrentGameStateSnapshot(
        String commanderFid,
        Long shipId,
        String shipType,
        String shipName,
        String loadoutHash,
        Long systemAddress,
        String systemName,
        Long bodyId,
        String bodyName,
        CommanderLocationMode commanderMode,
        FlightMode flightMode,
        String vehicleKind,
        Long activeVehicleId,
        Boolean activeOrganicSampling,
        BiologicalSamplingProcess samplingProcess
) {

    /**
     * The class of vessel, never its model.
     *
     * <p>{@code SRV} is a conventional Surface Recon Vehicle and {@code SLV} a
     * Ship-Launched Vessel; the Nomad is a model of the latter, and which model
     * it is belongs beside the class rather than in it. The vocabulary itself
     * lives in {@link AuxiliaryVehicleTypes}, which is where the journal's two
     * auxiliary channels are read into it.</p>
     */
    public static final String VEHICLE_SRV = AuxiliaryVehicleTypes.SRV;
    public static final String VEHICLE_SLV = AuxiliaryVehicleTypes.SLV;
    public static final String VEHICLE_SHIP = AuxiliaryVehicleTypes.SHIP;
    public static final String VEHICLE_UNKNOWN = AuxiliaryVehicleTypes.UNKNOWN;

    /**
     * The class a Nomad used to be recorded under. Read-only.
     *
     * <p>A behavior graph written before {@code SLV} existed still carries this
     * on its occurrences; {@link AuxiliaryVehicleTypes#canonicalKind} turns it
     * back into a class where one is needed. Nothing writes it any more.</p>
     */
    public static final String VEHICLE_NOMAD = AuxiliaryVehicleTypes.LEGACY_NOMAD;

    public static CurrentGameStateSnapshot unknown() {
        return new CurrentGameStateSnapshot(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                CommanderLocationMode.UNKNOWN,
                FlightMode.UNKNOWN,
                VEHICLE_UNKNOWN,
                null,
                null,
                null
        );
    }

    public CurrentGameStateSnapshot {
        commanderMode = Objects.requireNonNull(
                commanderMode,
                "commanderMode"
        );
        flightMode = Objects.requireNonNull(flightMode, "flightMode");
        vehicleKind = Objects.requireNonNull(vehicleKind, "vehicleKind");
        // A sampling process describes a sequence in progress. Retaining one
        // while sampling is known not to be active would leave identity and
        // stage asserting a sequence that ended.
        if (samplingProcess != null
                && Boolean.FALSE.equals(activeOrganicSampling)) {
            throw new IllegalArgumentException(
                    "an inactive sampling process must carry no identity"
            );
        }
    }
}
