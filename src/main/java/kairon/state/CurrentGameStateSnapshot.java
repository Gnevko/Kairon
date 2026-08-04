package kairon.state;

import kairon.semantics.AuxiliaryVehicleTypes;

import java.util.Objects;

/**
 * Immutable canonical projection of facts established by processed journal
 * events. Nullable fields retain the previous unknown-value semantics.
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
        String broadBodyType,
        String planetClass,
        String starType,
        CommanderLocationMode commanderMode,
        FlightMode flightMode,
        String vehicleKind,
        Long activeVehicleId,
        Integer biologicalSignalCount,
        Integer geologicalSignalCount,
        Boolean landable,
        Boolean wasDiscovered,
        Boolean wasMapped,
        Boolean wasFootfalled,
        Double distanceFromArrivalLs,
        Boolean bodyHasBiology,
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
                null,
                null,
                null,
                CommanderLocationMode.UNKNOWN,
                FlightMode.UNKNOWN,
                VEHICLE_UNKNOWN,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Legacy compatibility accessor.
     *
     * <p>Returns the legacy scalar from {@link BodyTypeCompatibilityProjection}
     * and is <strong>not</strong> the canonical classification. The canonical
     * body dimensions are {@code broadBodyType}, {@code planetClass} and
     * {@code starType}, and the model-facing context carries those three.</p>
     *
     * <p>No production caller remains: the behavior graph builds its
     * {@code ContextSnapshot.bodyType} from
     * {@link BodyTypeCompatibilityProjection} directly. The accessor survives
     * because {@code CurrentGameStateProjectorTest} still exercises it as a
     * public contract; removing it is a test change, not a dead-code
     * deletion.</p>
     */
    @Deprecated
    public String bodyType() {
        return BodyTypeCompatibilityProjection.compatibleBodyType(this);
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
