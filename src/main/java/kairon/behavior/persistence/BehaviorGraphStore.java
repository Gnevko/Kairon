package kairon.behavior.persistence;

import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.SystemEpisodeId;
import kairon.state.LastKnownShip;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistence boundary for per-ship aggregate graphs and exact system
 * episodes.
 */
public interface BehaviorGraphStore extends AutoCloseable {

    Optional<ShipBehaviorGraph> loadGraph(GraphId graphId);

    void saveGraph(ShipBehaviorGraph graph);

    Optional<SystemEpisode> loadEpisode(SystemEpisodeId episodeId);

    /**
     * Loads only the active episode for one graph, without scanning completed
     * episode history.
     */
    Optional<SystemEpisode> loadActiveEpisode(GraphId graphId);

    void saveEpisode(SystemEpisode episode);

    List<SystemEpisode> listEpisodes(GraphId graphId);

    Optional<GraphCursor> loadActiveCursor(GraphId graphId);

    void saveActiveCursor(GraphCursor cursor);

    boolean graphExists(GraphId graphId);

    void deleteGraph(GraphId graphId);

    Optional<EventOccurrence> findOccurrence(EventOccurrenceId occurrenceId);

    /**
     * The ship the graph was last active on, across runs.
     *
     * <p>Answered without a {@link GraphId} on purpose: the caller asking is
     * the one that has no ship yet. It is read once, while the runtime is being
     * wired, and seeds canonical state so a session that opens in an SRV or on
     * foot still knows which ship it belongs to — see {@link LastKnownShip} for
     * what {@code LoadGame} does instead and how often.</p>
     *
     * <p>A store that keeps no such memory answers empty, and the run then
     * behaves exactly as it did before this existed: no ship until the first
     * {@code Loadout}. That is the honest answer for an in-memory store, so it
     * is the default rather than something every implementation must restate.
     * </p>
     */
    default Optional<LastKnownShip> lastKnownShip() {
        return Optional.empty();
    }

    /**
     * Remembers the ship the graph is now active on.
     *
     * <p>Called only when canonical state established the ship itself — never
     * from the seeded fallback, which would write back what it just read and
     * could never be corrected.</p>
     */
    default void recordLastKnownShip(LastKnownShip ship) {
        Objects.requireNonNull(ship, "ship");
    }

    @Override
    default void close() {
    }

    /**
     * Runtime persistence failure. Callers may treat it as a graph subsystem
     * failure without leaking checked file-system exceptions through domain
     * services.
     */
    final class StoreException extends RuntimeException {

        public StoreException(String message) {
            super(Objects.requireNonNull(message, "message"));
        }

        public StoreException(String message, Throwable cause) {
            super(
                    Objects.requireNonNull(message, "message"),
                    Objects.requireNonNull(cause, "cause")
            );
        }
    }
}
