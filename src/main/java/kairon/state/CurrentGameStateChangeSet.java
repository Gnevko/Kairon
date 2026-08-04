package kairon.state;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable deterministic value-difference summary between two canonical
 * snapshots.
 */
public record CurrentGameStateChangeSet(
        Set<GameStateFacet> changedFacets
) {

    public CurrentGameStateChangeSet {
        Objects.requireNonNull(changedFacets, "changedFacets");
        EnumSet<GameStateFacet> copy = changedFacets.isEmpty()
                ? EnumSet.noneOf(GameStateFacet.class)
                : EnumSet.copyOf(changedFacets);
        changedFacets = Collections.unmodifiableSet(copy);
    }

    public static CurrentGameStateChangeSet between(
            CurrentGameStateSnapshot previous,
            CurrentGameStateSnapshot current
    ) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        EnumSet<GameStateFacet> changed =
                EnumSet.noneOf(GameStateFacet.class);

        addIfDifferent(
                changed,
                GameStateFacet.COMMANDER,
                previous.commanderFid(),
                current.commanderFid()
        );
        if (!Objects.equals(previous.shipId(), current.shipId())
                || !Objects.equals(previous.shipType(), current.shipType())
                || !Objects.equals(previous.shipName(), current.shipName())
                || !Objects.equals(
                        previous.loadoutHash(),
                        current.loadoutHash()
                )) {
            changed.add(GameStateFacet.SHIP);
        }
        if (!Objects.equals(
                previous.systemAddress(),
                current.systemAddress()
        ) || !Objects.equals(previous.systemName(), current.systemName())) {
            changed.add(GameStateFacet.SYSTEM);
        }
        if (!Objects.equals(previous.bodyId(), current.bodyId())
                || !Objects.equals(previous.bodyName(), current.bodyName())
                || !Objects.equals(
                        previous.broadBodyType(),
                        current.broadBodyType()
                )
                || !Objects.equals(previous.planetClass(), current.planetClass())
                || !Objects.equals(previous.starType(), current.starType())
                || !Objects.equals(
                        previous.geologicalSignalCount(),
                        current.geologicalSignalCount()
                )
                || !Objects.equals(previous.landable(), current.landable())
                || !Objects.equals(
                        previous.wasDiscovered(),
                        current.wasDiscovered()
                )
                || !Objects.equals(previous.wasMapped(), current.wasMapped())
                || !Objects.equals(
                        previous.wasFootfalled(),
                        current.wasFootfalled()
                )
                || !Objects.equals(
                        previous.distanceFromArrivalLs(),
                        current.distanceFromArrivalLs()
                )) {
            changed.add(GameStateFacet.BODY);
        }
        addIfDifferent(
                changed,
                GameStateFacet.PRESENCE,
                previous.commanderMode(),
                current.commanderMode()
        );
        addIfDifferent(
                changed,
                GameStateFacet.FLIGHT,
                previous.flightMode(),
                current.flightMode()
        );
        if (!Objects.equals(previous.vehicleKind(), current.vehicleKind())
                || !Objects.equals(
                        previous.activeVehicleId(),
                        current.activeVehicleId()
                )) {
            changed.add(GameStateFacet.VEHICLE);
        }
        if (!Objects.equals(
                previous.biologicalSignalCount(),
                current.biologicalSignalCount()
        )
                || !Objects.equals(
                        previous.bodyHasBiology(),
                        current.bodyHasBiology()
                )
                || !Objects.equals(
                        previous.activeOrganicSampling(),
                        current.activeOrganicSampling()
                )
                || !Objects.equals(
                        previous.samplingProcess(),
                        current.samplingProcess()
                )) {
            changed.add(GameStateFacet.BIOLOGICAL);
        }
        return new CurrentGameStateChangeSet(changed);
    }

    public boolean changed() {
        return !changedFacets.isEmpty();
    }

    private static void addIfDifferent(
            EnumSet<GameStateFacet> changed,
            GameStateFacet facet,
            Object previous,
            Object current
    ) {
        if (!Objects.equals(previous, current)) {
            changed.add(facet);
        }
    }
}
