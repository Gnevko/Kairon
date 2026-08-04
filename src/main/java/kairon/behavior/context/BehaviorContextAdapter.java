package kairon.behavior.context;

import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.GraphId;
import kairon.state.BodyTypeCompatibilityProjection;
import kairon.state.CurrentGameStateSnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure behavior-specific views derived from the canonical game state.
 */
public final class BehaviorContextAdapter {

    public Optional<GraphId> graphId(CurrentGameStateSnapshot state) {
        Objects.requireNonNull(state, "state");
        if (state.commanderFid() == null
                || state.shipId() == null
                || state.shipId() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new GraphId(
                state.commanderFid(),
                state.shipId()
        ));
    }

    public ContextSnapshot toContextSnapshot(CurrentGameStateSnapshot state) {
        Objects.requireNonNull(state, "state");
        return new ContextSnapshot(
                state.commanderFid(),
                state.shipId(),
                state.shipType(),
                state.shipName(),
                state.loadoutHash(),
                state.systemAddress(),
                state.systemName(),
                state.bodyId(),
                state.bodyName(),
                BodyTypeCompatibilityProjection.compatibleBodyType(state),
                state.commanderMode(),
                state.flightMode(),
                state.vehicleKind(),
                state.biologicalSignalCount(),
                state.geologicalSignalCount(),
                state.landable(),
                state.wasDiscovered(),
                state.wasMapped(),
                state.wasFootfalled(),
                state.distanceFromArrivalLs(),
                state.bodyHasBiology(),
                state.activeOrganicSampling()
        );
    }
}
