package kairon.state;

import java.util.Objects;

/**
 * The ship a Commander was last known to be flying, carried across runs.
 *
 * <p>It exists because {@code LoadGame} answers a different question from the
 * one canonical state asks. {@code LoadGame} says what the Commander is sitting
 * in when the session opens; canonical state wants their ship. Those agree
 * whenever the session opens in the ship and disagree whenever it does not —
 * measured across 340 {@code LoadGame} records in this project's journals, 22
 * of them named something that is not a ship: an SRV ({@code TestBuggy},
 * {@code Lander01}, reported with {@code FuelCapacity: 0.0}) or a suit
 * ({@code FlightSuit}, {@code ExplorationSuit_Class*}, reported with a
 * {@code ShipID} above four billion). One in fifteen sessions.</p>
 *
 * <p>The ship's own record is {@code Loadout}, which is emitted for a ship and
 * never for an SRV or a suit, and that is the only thing canonical state now
 * reads ship identity from. But {@code Loadout} does not arrive at all in a
 * session that opens in an SRV and never boards the ship, so without this the
 * whole session would have no ship — and the behaviour graph, which is isolated
 * per Commander and concrete ship, would have nowhere to write. On 2026-08-08 it
 * wrote a session into a graph keyed on the Nomad while the Mandalay's graph,
 * carrying every episode of the visit, sat untouched beside it.</p>
 *
 * <p>So the last ship the graph was active on is remembered and used as the
 * starting point of the next run. It is a seed, not an authority: the first
 * {@code Loadout} of the session replaces it, and a different Commander clears
 * it. An SRV cannot be swapped into another ship, so a session that opens in one
 * opens attached to exactly the ship this records.</p>
 *
 * @param commanderFid the Commander this ship belongs to
 * @param shipId       the concrete ship id, always positive
 * @param shipType     the ship's internal type name, or null if never seen
 * @param shipName     the Commander's name for it, or null
 */
public record LastKnownShip(
        String commanderFid,
        long shipId,
        String shipType,
        String shipName
) {

    public LastKnownShip {
        Objects.requireNonNull(commanderFid, "commanderFid");
        if (commanderFid.isBlank()) {
            throw new IllegalArgumentException(
                    "commanderFid must not be blank"
            );
        }
        if (shipId <= 0) {
            throw new IllegalArgumentException(
                    "shipId must be positive, was " + shipId
            );
        }
    }
}
