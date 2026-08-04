package kairon.behavior.graph;

import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;

import java.util.Optional;

/**
 * Read-only, active-episode-only boundary for occurrence inspection.
 */
public interface BehaviorGraphOccurrenceQuery {

    ActiveEpisodeNodeOccurrencesSnapshot getActiveEpisodeOccurrences(
            GraphId graphId,
            NormalizedEventType eventType
    );

    Optional<EventOccurrenceDetailsSnapshot>
            getActiveEpisodeOccurrenceDetails(
                    GraphId graphId,
                    SystemEpisodeId episodeId,
                    EventOccurrenceId occurrenceId
            );
}
