package kairon.behavior.event;

import kairon.behavior.model.EdgeKey;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.model.TransitionOccurrenceId;
import kairon.behavior.normalize.NormalizedEventType;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal graph notifications. They never travel through ObservationBus.
 */
public sealed interface BehaviorGraphEvent permits
        BehaviorGraphEvent.BehaviorGraphCreated,
        BehaviorGraphEvent.ActiveGraphChanged,
        BehaviorGraphEvent.SystemEpisodeStarted,
        BehaviorGraphEvent.SystemEpisodeCompleted,
        BehaviorGraphEvent.EventTypeNodeCreated,
        BehaviorGraphEvent.TransitionEdgeCreated,
        BehaviorGraphEvent.EventOccurrenceRecorded,
        BehaviorGraphEvent.OccurrenceTransitionRecorded,
        BehaviorGraphEvent.GraphCursorChanged,
        BehaviorGraphEvent.BehaviorGraphUpdated,
        BehaviorGraphEvent.NextEventPredictionChanged,
        BehaviorGraphEvent.ReplayCompleted {

    GraphId graphId();

    Instant occurredAt();

    record BehaviorGraphCreated(
            GraphId graphId,
            Instant occurredAt
    ) implements BehaviorGraphEvent {

        public BehaviorGraphCreated {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record ActiveGraphChanged(
            GraphId graphId,
            Optional<GraphId> previousGraphId,
            Instant occurredAt
    ) implements BehaviorGraphEvent {

        public ActiveGraphChanged {
            Objects.requireNonNull(graphId, "graphId");
            previousGraphId = Objects.requireNonNull(
                    previousGraphId,
                    "previousGraphId"
            );
            if (previousGraphId.filter(graphId::equals).isPresent()) {
                throw new IllegalArgumentException(
                        "previousGraphId must differ from graphId"
                );
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record SystemEpisodeStarted(
            GraphId graphId,
            SystemEpisodeId episodeId,
            Instant occurredAt
    ) implements BehaviorGraphEvent {

        public SystemEpisodeStarted {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(episodeId, "episodeId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record SystemEpisodeCompleted(
            GraphId graphId,
            SystemEpisodeId episodeId,
            Instant occurredAt
    ) implements BehaviorGraphEvent {

        public SystemEpisodeCompleted {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(episodeId, "episodeId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record EventTypeNodeCreated(
            GraphId graphId,
            NormalizedEventType eventType,
            Instant occurredAt
    ) implements BehaviorGraphEvent {

        public EventTypeNodeCreated {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record TransitionEdgeCreated(
            GraphId graphId,
            EdgeKey edgeKey,
            Instant occurredAt
    ) implements BehaviorGraphEvent {

        public TransitionEdgeCreated {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(edgeKey, "edgeKey");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record EventOccurrenceRecorded(
            GraphId graphId,
            EventOccurrenceId occurrenceId,
            Instant occurredAt
    ) implements BehaviorGraphEvent {

        public EventOccurrenceRecorded {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(occurrenceId, "occurrenceId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record OccurrenceTransitionRecorded(
            GraphId graphId,
            TransitionOccurrenceId transitionId,
            Instant occurredAt
    ) implements BehaviorGraphEvent {

        public OccurrenceTransitionRecorded {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(transitionId, "transitionId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record GraphCursorChanged(
            GraphId graphId,
            Optional<GraphCursor> cursor,
            Instant occurredAt
    ) implements BehaviorGraphEvent {

        public GraphCursorChanged {
            Objects.requireNonNull(graphId, "graphId");
            cursor = Objects.requireNonNull(cursor, "cursor");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record BehaviorGraphUpdated(
            GraphId graphId,
            Instant occurredAt
    ) implements BehaviorGraphEvent {

        public BehaviorGraphUpdated {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record NextEventPredictionChanged(
            GraphId graphId,
            EventOccurrenceId currentOccurrenceId,
            Instant occurredAt
    ) implements BehaviorGraphEvent {

        public NextEventPredictionChanged {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(
                    currentOccurrenceId,
                    "currentOccurrenceId"
            );
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record ReplayCompleted(
            GraphId graphId,
            Instant occurredAt
    ) implements BehaviorGraphEvent {

        public ReplayCompleted {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
