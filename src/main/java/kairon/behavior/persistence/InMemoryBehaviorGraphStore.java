package kairon.behavior.persistence;

import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.SystemEpisodeId;

import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Deterministic, synchronized store for unit tests and transient runs.
 */
public final class InMemoryBehaviorGraphStore
        implements BehaviorGraphStore {

    private static final Comparator<SystemEpisode> EPISODE_ORDER =
            Comparator.comparing(SystemEpisode::startedAt)
                    .thenComparing(SystemEpisode::id);

    private final NavigableMap<GraphId, ShipBehaviorGraph> graphs =
            new TreeMap<>();
    private final NavigableMap<SystemEpisodeId, SystemEpisode> episodes =
            new TreeMap<>();

    @Override
    public synchronized Optional<ShipBehaviorGraph> loadGraph(GraphId graphId) {
        return Optional.ofNullable(
                graphs.get(Objects.requireNonNull(graphId, "graphId"))
        );
    }

    @Override
    public synchronized void saveGraph(ShipBehaviorGraph graph) {
        Objects.requireNonNull(graph, "graph");
        graphs.put(graph.graphId(), graph);
    }

    @Override
    public synchronized Optional<SystemEpisode> loadEpisode(
            SystemEpisodeId episodeId
    ) {
        return Optional.ofNullable(
                episodes.get(Objects.requireNonNull(episodeId, "episodeId"))
        );
    }

    @Override
    public synchronized Optional<SystemEpisode> loadActiveEpisode(
            GraphId graphId
    ) {
        Objects.requireNonNull(graphId, "graphId");
        return episodes.values().stream()
                .filter(episode -> episode.graphId().equals(graphId))
                .filter(SystemEpisode::active)
                .findFirst();
    }

    @Override
    public synchronized void saveEpisode(SystemEpisode episode) {
        Objects.requireNonNull(episode, "episode");
        if (episode.active()) {
            boolean competingActive = episodes.values().stream()
                    .anyMatch(existing ->
                            existing.active()
                                    && existing.graphId().equals(
                                            episode.graphId()
                                    )
                                    && !existing.id().equals(episode.id()));
            if (competingActive) {
                throw new StoreException(
                        "another active episode already exists for graph "
                                + episode.graphId().canonicalValue()
                );
            }
        }
        SystemEpisode existing = episodes.get(episode.id());
        if (existing != null
                && !existing.graphId().equals(episode.graphId())) {
            throw new StoreException(
                    "episode ID belongs to another graph: " + episode.id()
            );
        }
        episodes.put(episode.id(), episode);
    }

    @Override
    public synchronized List<SystemEpisode> listEpisodes(GraphId graphId) {
        Objects.requireNonNull(graphId, "graphId");
        return episodes.values().stream()
                .filter(episode -> episode.graphId().equals(graphId))
                .sorted(EPISODE_ORDER)
                .toList();
    }

    @Override
    public synchronized Optional<GraphCursor> loadActiveCursor(
            GraphId graphId
    ) {
        return loadGraph(graphId).map(ShipBehaviorGraph::cursor);
    }

    @Override
    public synchronized void saveActiveCursor(GraphCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        ShipBehaviorGraph graph = graphs.get(cursor.graphId());
        if (graph == null) {
            throw new StoreException(
                    "cannot save cursor for unknown graph: "
                            + cursor.graphId().canonicalValue()
            );
        }
        graphs.put(cursor.graphId(), graph.withCursor(cursor));
    }

    @Override
    public synchronized boolean graphExists(GraphId graphId) {
        return graphs.containsKey(Objects.requireNonNull(graphId, "graphId"));
    }

    @Override
    public synchronized void deleteGraph(GraphId graphId) {
        Objects.requireNonNull(graphId, "graphId");
        graphs.remove(graphId);
        episodes.entrySet().removeIf(
                entry -> entry.getValue().graphId().equals(graphId)
        );
    }

    @Override
    public synchronized Optional<EventOccurrence> findOccurrence(
            EventOccurrenceId occurrenceId
    ) {
        Objects.requireNonNull(occurrenceId, "occurrenceId");
        EventOccurrence found = null;
        for (SystemEpisode episode : episodes.values()) {
            for (EventOccurrence occurrence : episode.timeline()) {
                if (!occurrence.id().equals(occurrenceId)) {
                    continue;
                }
                if (found != null && !found.equals(occurrence)) {
                    throw new StoreException(
                            "occurrence ID collision: " + occurrenceId
                    );
                }
                found = occurrence;
            }
        }
        return Optional.ofNullable(found);
    }
}
