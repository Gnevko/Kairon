package kairon.behavior.context;

import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.GraphId;
import kairon.state.BodyTypeCompatibilityProjection;
import kairon.state.CurrentGameStateSnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure behavior-specific views derived from the canonical game state.
 *
 * <p>Two sources, because a context snapshot answers two different questions.
 * Where the Commander is, which ship, in what flight mode — canonical state.
 * What the body there is like — the {@link BodyDetailLookup} handed in with the
 * observation, since body detail is the current system's rather than the
 * ship's. Nothing is derived twice: the coarse type, the class and the counts
 * come from one place each.</p>
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

    public ContextSnapshot toContextSnapshot(
            CurrentGameStateSnapshot state,
            BodyDetailLookup bodies
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(bodies, "bodies");
        BodyDetail body = Objects.requireNonNull(
                bodies.detailOf(state.systemAddress(), state.bodyId()),
                "body detail"
        );
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
                BodyTypeCompatibilityProjection.compatibleBodyType(
                        body.broadBodyType(),
                        body.planetClass(),
                        body.starType()
                ),
                state.commanderMode(),
                state.flightMode(),
                state.vehicleKind(),
                body.biologicalSignalCount(),
                body.geologicalSignalCount(),
                body.landable(),
                body.wasDiscovered(),
                body.wasMapped(),
                body.wasFootfalled(),
                body.distanceFromArrivalLs(),
                body.hasBiology(),
                state.activeOrganicSampling()
        );
    }
}
