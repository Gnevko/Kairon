package kairon.behavior.persistence;

import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.SystemEpisodeId;

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
