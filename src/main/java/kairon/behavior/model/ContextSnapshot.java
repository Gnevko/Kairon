package kairon.behavior.model;

import kairon.state.CommanderLocationMode;
import kairon.state.FlightMode;

/**
 * Behavior-persistence view of the canonical game state captured with an
 * occurrence. Nullable fields mean the journal has not established that fact
 * in the current processing path.
 */
public record ContextSnapshot(
        String commanderFid,
        Long shipId,
        String shipType,
        String shipName,
        String loadoutHash,
        Long systemAddress,
        String systemName,
        Long bodyId,
        String bodyName,
        String bodyType,
        CommanderLocationMode commanderMode,
        FlightMode flightMode,
        String vehicleKind,
        Integer biologicalSignalCount,
        Integer geologicalSignalCount,
        Boolean landable,
        Boolean wasDiscovered,
        Boolean wasMapped,
        Boolean wasFootfalled,
        Double distanceFromArrivalLs,
        Boolean bodyHasBiology,
        Boolean activeOrganicSampling
) {
}
