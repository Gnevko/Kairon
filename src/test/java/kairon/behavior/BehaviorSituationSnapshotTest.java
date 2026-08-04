package kairon.behavior;

import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.graph.BehaviorGraphApplyStatus;
import kairon.behavior.graph.BehaviorGraphChangeSet;
import kairon.behavior.model.ContextKey;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.EventOccurrenceSource;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.PredictionBasis;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.behavior.snapshot.ActiveEpisodeSituation;
import kairon.behavior.snapshot.BehaviorSituationCaptureStatus;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.behavior.snapshot.SituationNextEventPrediction;
import kairon.behavior.snapshot.SituationOccurrence;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.ObservationDraft.SourcePosition;
import kairon.observation.ObservationPayload;
import kairon.observation.PublishedObservation;
import kairon.projection.ProjectedObservation;
import kairon.projection.SemanticEnvelopeFactory;
import kairon.semantics.SemanticObservationEnvelope;
import kairon.state.CurrentGameStateSnapshot;
import kairon.state.CurrentGameStateChangeSet;
import kairon.state.AppliedObservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BehaviorSituationSnapshotTest {

    private static final GraphId GRAPH_ID = new GraphId("F900", 90);
    private static final SystemEpisodeId EPISODE_ID =
            new SystemEpisodeId("episode-90");
    private static final EventOccurrenceId OCCURRENCE_ID =
            new EventOccurrenceId("occurrence-90");
    private static final Instant TIME =
            Instant.parse("2026-07-30T15:00:00Z");
    private static final GraphCursor CURSOR = new GraphCursor(
            GRAPH_ID,
            EPISODE_ID,
            OCCURRENCE_ID,
            NormalizedEventType.SYSTEM_ENTRY,
            TIME
    );

    @Test
    void snapshotDefensivelyCopiesTrajectoryCountsAndPredictions() {
        List<SituationOccurrence> trajectory = new ArrayList<>();
        trajectory.add(currentOccurrence());
        Map<NormalizedEventType, Long> counts = new TreeMap<>();
        counts.put(NormalizedEventType.SYSTEM_ENTRY, 1L);
        ActiveEpisodeSituation episode = new ActiveEpisodeSituation(
                GRAPH_ID,
                EPISODE_ID,
                9001,
                "Immutable",
                TIME,
                CURSOR,
                trajectory,
                currentOccurrence(),
                1,
                counts,
                4,
                2
        );
        BehaviorGraphApplyResult applyResult = applied(
                GRAPH_ID,
                EPISODE_ID,
                CURSOR,
                4,
                2
        );
        List<SituationNextEventPrediction> predictions =
                new ArrayList<>();
        predictions.add(new SituationNextEventPrediction(
                NormalizedEventType.SYSTEM_ENTRY,
                NormalizedEventType.FSS_DISCOVERY_SCAN,
                1.0,
                PredictionBasis.GLOBAL,
                1.0,
                2,
                0,
                0.0,
                ContextKey.EMPTY,
                2.0
        ));
        BehaviorSituationSnapshot snapshot =
                BehaviorSituationSnapshot.available(
                        applyResult,
                        episode,
                        predictions
                );

        trajectory.clear();
        counts.clear();
        predictions.clear();

        assertEquals(1, snapshot.activeEpisode()
                .orElseThrow().trajectory().size());
        assertEquals(
                Map.of(NormalizedEventType.SYSTEM_ENTRY, 1L),
                snapshot.activeEpisode()
                        .orElseThrow()
                        .occurrenceCounts()
        );
        assertEquals(1, snapshot.likelyNext().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.likelyNext().clear()
        );
    }

    @Test
    void correlationValidationRejectsBusGraphEpisodeCursorAndRevisions() {
        ActiveEpisodeSituation episode = episode(
                GRAPH_ID,
                EPISODE_ID,
                CURSOR,
                4,
                2
        );
        BehaviorGraphApplyResult applyResult = applied(
                GRAPH_ID,
                EPISODE_ID,
                CURSOR,
                4,
                2
        );
        BehaviorSituationSnapshot valid =
                BehaviorSituationSnapshot.available(
                        applyResult,
                        episode,
                        List.of()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> new BehaviorSituationSnapshot(
                        2,
                        BehaviorSituationCaptureStatus.AVAILABLE,
                        applyResult,
                        Optional.of(episode),
                        List.of()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BehaviorSituationSnapshot.available(
                        applyResult,
                        episode(GRAPH_ID, EPISODE_ID, CURSOR, 5, 2),
                        List.of()
                )
        );

        SystemEpisodeId otherEpisode =
                new SystemEpisodeId("other-episode");
        GraphCursor otherEpisodeCursor = new GraphCursor(
                GRAPH_ID,
                otherEpisode,
                OCCURRENCE_ID,
                NormalizedEventType.SYSTEM_ENTRY,
                TIME
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BehaviorSituationSnapshot.available(
                        applyResult,
                        episode(
                                GRAPH_ID,
                                otherEpisode,
                                otherEpisodeCursor,
                                4,
                                2
                        ),
                        List.of()
                )
        );

        GraphId otherGraph = new GraphId("F901", 91);
        GraphCursor otherGraphCursor = new GraphCursor(
                otherGraph,
                EPISODE_ID,
                OCCURRENCE_ID,
                NormalizedEventType.SYSTEM_ENTRY,
                TIME
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BehaviorSituationSnapshot.available(
                        applyResult,
                        episode(
                                otherGraph,
                                EPISODE_ID,
                                otherGraphCursor,
                                4,
                                2
                        ),
                        List.of()
                )
        );

        GraphCursor otherCursor = new GraphCursor(
                GRAPH_ID,
                EPISODE_ID,
                new EventOccurrenceId("other-occurrence"),
                NormalizedEventType.SYSTEM_ENTRY,
                TIME
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BehaviorSituationSnapshot.available(
                        applyResult,
                        episode(
                                GRAPH_ID,
                                EPISODE_ID,
                                otherCursor,
                                4,
                                2
                        ),
                        List.of()
                )
        );

        BehaviorGraphApplyResult differentResult = applied(
                GRAPH_ID,
                EPISODE_ID,
                CURSOR,
                5,
                2
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectedObservation(
                        observation(1),
                        applied(observation(1)),
                        CurrentGameStateChangeSet.between(
                                CurrentGameStateSnapshot.unknown(),
                                CurrentGameStateSnapshot.unknown()
                        ),
                        differentResult,
                        valid,
                        envelope(observation(1))
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectedObservation(
                        observation(2),
                        applied(observation(2)),
                        CurrentGameStateChangeSet.between(
                                CurrentGameStateSnapshot.unknown(),
                                CurrentGameStateSnapshot.unknown()
                        ),
                        applyResult,
                        valid,
                        envelope(observation(2))
                )
        );
    }

    @Test
    void unavailableStatusesCannotClaimSituationPayload() {
        BehaviorGraphApplyResult disabled =
                BehaviorGraphApplyResult.disabled(1);
        BehaviorSituationSnapshot unavailable =
                BehaviorSituationSnapshot.unavailable(
                        disabled,
                        BehaviorSituationCaptureStatus.GRAPH_DISABLED
                );

        assertEquals(
                BehaviorSituationCaptureStatus.GRAPH_DISABLED,
                unavailable.captureStatus()
        );
        assertEquals(Optional.empty(), unavailable.activeEpisode());
        assertEquals(List.of(), unavailable.likelyNext());
        assertThrows(
                IllegalArgumentException.class,
                () -> new BehaviorSituationSnapshot(
                        1,
                        BehaviorSituationCaptureStatus.AVAILABLE,
                        disabled,
                        Optional.of(episode(
                                GRAPH_ID,
                                EPISODE_ID,
                                CURSOR,
                                4,
                                2
                        )),
                        List.of()
                )
        );
    }

    private static ActiveEpisodeSituation episode(
            GraphId graphId,
            SystemEpisodeId episodeId,
            GraphCursor cursor,
            long graphVersion,
            long topologyVersion
    ) {
        SituationOccurrence current = new SituationOccurrence(
                cursor.occurrenceId(),
                0,
                cursor.eventType(),
                EventOccurrenceSource.JOURNAL,
                cursor.updatedAt(),
                true,
                null
        );
        return new ActiveEpisodeSituation(
                graphId,
                episodeId,
                9001,
                "Correlation",
                TIME,
                cursor,
                List.of(current),
                current,
                1,
                Map.of(cursor.eventType(), 1L),
                graphVersion,
                topologyVersion
        );
    }

    private static SituationOccurrence currentOccurrence() {
        return new SituationOccurrence(
                OCCURRENCE_ID,
                0,
                NormalizedEventType.SYSTEM_ENTRY,
                EventOccurrenceSource.JOURNAL,
                TIME,
                true,
                null
        );
    }

    private static BehaviorGraphApplyResult applied(
            GraphId graphId,
            SystemEpisodeId episodeId,
            GraphCursor cursor,
            long graphVersion,
            long topologyVersion
    ) {
        return new BehaviorGraphApplyResult(
                1,
                BehaviorGraphApplyStatus.APPLIED,
                new BehaviorGraphChangeSet(
                        false,
                        false,
                        true,
                        true,
                        true,
                        false
                ),
                Optional.of(graphId),
                Optional.of(episodeId),
                Optional.of(cursor),
                OptionalLong.of(graphVersion),
                OptionalLong.of(topologyVersion)
        );
    }

    private static PublishedObservation<TestPayload> observation(
            long busSequence
    ) {
        return new PublishedObservation<>(
                "situation-" + busSequence,
                busSequence,
                new ObservationSource("situation-test", "snapshot"),
                new TestSourcePosition(busSequence),
                Optional.empty(),
                TIME,
                ObservationCaptureMode.LIVE,
                "test/v1",
                new TestPayload()
        );
    }

    private static AppliedObservation applied(
            PublishedObservation<?> observation
    ) {
        return AppliedObservation.of(
                observation,
                CurrentGameStateSnapshot.unknown(),
                CurrentGameStateSnapshot.unknown(),
                CurrentGameStateSnapshot.unknown(),
                List.of()
        );
    }

    private static SemanticObservationEnvelope envelope(
            PublishedObservation<TestPayload> observation
    ) {
        return SemanticEnvelopeFactory.production()
                .create(observation, applied(observation));
    }

    private record TestPayload() implements ObservationPayload {
    }

    private record TestSourcePosition(long sequence)
            implements SourcePosition {
    }
}
