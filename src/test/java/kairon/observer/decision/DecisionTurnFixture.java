package kairon.observer.decision;

import kairon.semantics.BodyIdentity;
import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.graph.BehaviorGraphApplyStatus;
import kairon.behavior.graph.BehaviorGraphChangeSet;
import kairon.behavior.graph.BehaviorGraphIds;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.ObservationDraft.SourcePosition;
import kairon.observation.ObservationPayload;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.status.StatusSnapshotObservation;
import kairon.projection.ProjectedObservation;
import kairon.projection.SemanticEnvelopeFactory;
import kairon.semantics.SemanticEffectAccumulator;
import kairon.state.CurrentGameStateProjection;
import kairon.state.CurrentGameStateProjector;
import kairon.state.CurrentGameStateSnapshot;
import kairon.system.CurrentSystemRegistry;
import kairon.system.SystemRegistrySnapshot;
import kairon.system.VisitIdentity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Builds real turn inputs from raw journal JSON.
 *
 * <p>Uses the production parser, adapter, state projector and semantic
 * envelope factory, so a decision test asserts against the same envelopes the runtime
 * produces. Only the behavior-graph situation is synthesised, because a
 * scripted cursor is exactly what the graph-context cases need;
 * {@link DecisionProductionPipeline} drives the real graph instead.</p>
 */
final class DecisionTurnFixture {

    static final GraphId GRAPH_ID = new GraphId("F-DECISION", 42L);
    static final SystemEpisodeId EPISODE_ID =
            new SystemEpisodeId("episode-decision");
    static final Instant EPISODE_START =
            Instant.parse("2026-07-30T12:00:00Z");

    private static final ObservationSource SOURCE =
            new ObservationSource("elite-journal", "decision-test");
    private static final ObjectMapper STATUS_JSON = new ObjectMapper();

    private final JournalLineParser parser = new JournalLineParser();
    private final JournalObservationAdapter adapter =
            new JournalObservationAdapter(SOURCE);
    private final CurrentGameStateProjector projector =
            new CurrentGameStateProjector();
    /**
     * The real registry, driven exactly as the coordinator drives it.
     *
     * <p>It used to be a stubbed empty snapshot, which was invisible while
     * nothing read it and became a silent loss of every body fact the moment
     * {@code context.body} started reading it. A fixture that stands in for a
     * production collaborator has to keep standing in for it.</p>
     */
    private final CurrentSystemRegistry systemRegistry =
            new CurrentSystemRegistry();
    private final SemanticEnvelopeFactory semantics =
            SemanticEnvelopeFactory.production();
    private final SemanticEffectAccumulator effects;

    private long sourceOffset;
    private long busSequence;

    DecisionTurnFixture() {
        this(SemanticEffectAccumulator.DEFAULT_MAX_RETAINED_ENVELOPES);
    }

    /** A smaller bound forces the accumulator to fold and report suppression. */
    DecisionTurnFixture(int maxRetainedEnvelopes) {
        this.effects = new SemanticEffectAccumulator(maxRetainedEnvelopes);
    }

    /** The registry applied to this observation, as the coordinator does it. */
    private SystemRegistrySnapshot registrySnapshot(
            PublishedObservation<?> observation,
            CurrentGameStateProjection projection
    ) {
        CurrentGameStateSnapshot state = projection.currentState();
        return systemRegistry.applyAndCapture(
                observation,
                new VisitIdentity(
                        state.commanderFid(),
                        state.shipId(),
                        state.systemAddress(),
                        state.systemName()
                )
        );
    }

    /** Projects one event with the behavior graph switched off. */
    ProjectedObservation graphDisabled(String rawJson) {
        PublishedObservation<JournalEventObservation> observation =
                publish(rawJson);
        CurrentGameStateProjection projection =
                projector.applyAndCapture(observation);
        BehaviorGraphApplyResult apply =
                BehaviorGraphApplyResult.disabled(
                        observation.busSequence()
                );
        return record(new ProjectedObservation(
                observation,
                projection.applied(),
                projection.changes(),
                apply,
                BehaviorSituationSnapshot.unavailable(
                        apply,
                        BehaviorSituationCaptureStatus.GRAPH_DISABLED
                ),
                semantics.create(observation, projection.applied()),
                registrySnapshot(observation, projection)
        ));
    }

    /**
     * Projects one event against a scripted active episode.
     *
     * @param trajectory  normalized types, the first of which must be
     *                    {@code SYSTEM_ENTRY}; the last owns the cursor
     * @param applied     whether this observation committed the cursor
     * @param predictions how many likely-next entries to attach
     */
    ProjectedObservation graphed(
            String rawJson,
            List<NormalizedEventType> trajectory,
            boolean applied,
            int predictions
    ) {
        return graphed(
                rawJson,
                trajectory.stream()
                        .map(TrajectoryEntry::journal)
                        .toList(),
                applied,
                predictions,
                ContextKey.EMPTY
        );
    }

    /**
     * Projects one event against a scripted episode with named predictions.
     *
     * <p>The probabilities are split evenly and in the deterministic order the
     * snapshot requires, so what a test asserts is that the exact numbers the
     * calculation produced reach the model — not that a number was computed
     * here.</p>
     */
    ProjectedObservation graphedPredicting(
            String rawJson,
            List<TrajectoryEntry> trajectory,
            List<NormalizedEventType> predicted
    ) {
        return graphed(
                rawJson,
                trajectory,
                true,
                predicted,
                ContextKey.EMPTY
        );
    }

    /**
     * Projects one event against a scripted episode with explicit provenance.
     *
     * <p>Provenance is scripted rather than derived, exactly as the graph layer
     * records it, so a Status-derived cursor is expressible without inferring
     * anything from the normalized type.</p>
     */
    ProjectedObservation graphed(
            String rawJson,
            List<TrajectoryEntry> trajectory,
            boolean applied,
            int predictions,
            ContextKey contextKey
    ) {
        List<NormalizedEventType> predicted = new ArrayList<>(predictions);
        for (int index = 0; index < predictions; index++) {
            predicted.add(NormalizedEventType.of("PRED_%02d".formatted(index)));
        }
        return graphed(rawJson, trajectory, applied, predicted, contextKey);
    }

    ProjectedObservation graphed(
            String rawJson,
            List<TrajectoryEntry> trajectory,
            boolean applied,
            List<NormalizedEventType> predicted,
            ContextKey contextKey
    ) {
        PublishedObservation<JournalEventObservation> observation =
                publish(rawJson);
        CurrentGameStateProjection projection =
                projector.applyAndCapture(observation);
        // A committed cursor must carry the identity the graph would have
        // minted for this observation, otherwise "this trigger owns the
        // cursor" cannot be asserted against production's own derivation.
        List<SituationOccurrence> occurrences = occurrences(
                trajectory,
                applied
                        ? BehaviorGraphIds.journalOccurrence(
                                GRAPH_ID,
                                observation.observationId()
                        )
                        : null
        );
        SituationOccurrence current = occurrences.getLast();
        GraphCursor cursor = new GraphCursor(
                GRAPH_ID,
                EPISODE_ID,
                current.occurrenceId(),
                current.eventType(),
                current.occurredAt()
        );
        BehaviorGraphApplyResult apply = new BehaviorGraphApplyResult(
                observation.busSequence(),
                applied
                        ? BehaviorGraphApplyStatus.APPLIED
                        : BehaviorGraphApplyStatus.NOT_APPLICABLE,
                applied
                        ? new BehaviorGraphChangeSet(
                                false,
                                occurrences.size() == 1,
                                true,
                                true,
                                true,
                                false
                        )
                        : BehaviorGraphChangeSet.none(),
                Optional.of(GRAPH_ID),
                Optional.of(EPISODE_ID),
                Optional.of(cursor),
                OptionalLong.of(100L),
                OptionalLong.of(50L)
        );
        ActiveEpisodeSituation active = new ActiveEpisodeSituation(
                GRAPH_ID,
                EPISODE_ID,
                7101L,
                "Fixture System",
                EPISODE_START,
                cursor,
                occurrences,
                current,
                occurrences.size(),
                counts(occurrences),
                100L,
                50L
        );
        return record(new ProjectedObservation(
                observation,
                projection.applied(),
                projection.changes(),
                apply,
                BehaviorSituationSnapshot.available(
                        apply,
                        active,
                        predictions(
                                current.eventType(),
                                predicted,
                                contextKey
                        )
                ),
                semantics.create(observation, projection.applied()),
                registrySnapshot(observation, projection)
        ));
    }

    /**
     * Projects one event whose apply reported {@code APPLIED} without
     * committing an occurrence of its own.
     *
     * <p>{@code APPLIED} reports {@link BehaviorGraphChangeSet#changed()},
     * which an owner switch, an episode switch or a bare revision bump also
     * satisfies. In those applies the cursor still points at an occurrence an
     * earlier observation committed, and this observation owns nothing.</p>
     */
    ProjectedObservation graphedWithoutOwnOccurrence(
            String rawJson,
            List<NormalizedEventType> trajectory
    ) {
        PublishedObservation<JournalEventObservation> observation =
                publish(rawJson);
        CurrentGameStateProjection projection =
                projector.applyAndCapture(observation);
        List<SituationOccurrence> occurrences = occurrences(
                trajectory.stream()
                        .map(TrajectoryEntry::journal)
                        .toList(),
                BehaviorGraphIds.journalOccurrence(
                        GRAPH_ID,
                        "earlier-observation"
                )
        );
        SituationOccurrence current = occurrences.getLast();
        GraphCursor cursor = new GraphCursor(
                GRAPH_ID,
                EPISODE_ID,
                current.occurrenceId(),
                current.eventType(),
                current.occurredAt()
        );
        BehaviorGraphApplyResult apply = new BehaviorGraphApplyResult(
                observation.busSequence(),
                BehaviorGraphApplyStatus.APPLIED,
                new BehaviorGraphChangeSet(
                        false,
                        false,
                        false,
                        false,
                        true,
                        false
                ),
                Optional.of(GRAPH_ID),
                Optional.of(EPISODE_ID),
                Optional.of(cursor),
                OptionalLong.of(101L),
                OptionalLong.of(50L)
        );
        ActiveEpisodeSituation active = new ActiveEpisodeSituation(
                GRAPH_ID,
                EPISODE_ID,
                7101L,
                "Fixture System",
                EPISODE_START,
                cursor,
                occurrences,
                current,
                occurrences.size(),
                counts(occurrences),
                101L,
                50L
        );
        return record(new ProjectedObservation(
                observation,
                projection.applied(),
                projection.changes(),
                apply,
                BehaviorSituationSnapshot.available(apply, active, List.of()),
                semantics.create(observation, projection.applied()),
                registrySnapshot(observation, projection)
        ));
    }

    /** Projects one event whose graph situation could not be captured. */
    ProjectedObservation graphUnavailable(
            String rawJson,
            BehaviorGraphApplyStatus applyStatus,
            BehaviorSituationCaptureStatus captureStatus
    ) {
        PublishedObservation<JournalEventObservation> observation =
                publish(rawJson);
        CurrentGameStateProjection projection =
                projector.applyAndCapture(observation);
        BehaviorGraphApplyResult apply = switch (applyStatus) {
            case DISABLED -> BehaviorGraphApplyResult.disabled(
                    observation.busSequence()
            );
            case FAILED -> BehaviorGraphApplyResult.failed(
                    observation.busSequence()
            );
            case NO_GRAPH_ID -> BehaviorGraphApplyResult.noGraphId(
                    observation.busSequence()
            );
            default -> throw new IllegalArgumentException(
                    "unsupported unavailable apply status"
            );
        };
        return record(new ProjectedObservation(
                observation,
                projection.applied(),
                projection.changes(),
                apply,
                BehaviorSituationSnapshot.unavailable(apply, captureStatus),
                semantics.create(observation, projection.applied()),
                registrySnapshot(observation, projection)
        ));
    }

    /** Projects one Status snapshot: a hidden source that is never a trigger. */
    ProjectedObservation status(long flags) {
        String rawJson = "{\"timestamp\":\"2026-07-30T12:04:00Z\",\"Flags\":"
                + flags + "}";
        PublishedObservation<ObservationPayload> observation;
        try {
            observation = new PublishedObservation<>(
                    "status-" + (busSequence + 1),
                    ++busSequence,
                    SOURCE,
                    new FixtureSourcePosition(busSequence),
                    Optional.of(EPISODE_START),
                    EPISODE_START,
                    ObservationCaptureMode.REPLAY,
                    StatusSnapshotObservation.SCHEMA_VERSION,
                    new StatusSnapshotObservation(
                            rawJson,
                            STATUS_JSON.readTree(rawJson),
                            Optional.of(EPISODE_START),
                            OptionalLong.of(flags),
                            OptionalLong.empty(),
                            OptionalInt.empty()
                    )
            );
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        CurrentGameStateProjection projection =
                projector.applyAndCapture(observation);
        BehaviorGraphApplyResult apply =
                BehaviorGraphApplyResult.disabled(
                        observation.busSequence()
                );
        return record(new ProjectedObservation(
                observation,
                projection.applied(),
                projection.changes(),
                apply,
                BehaviorSituationSnapshot.unavailable(
                        apply,
                        BehaviorSituationCaptureStatus.GRAPH_DISABLED
                ),
                semantics.create(observation, projection.applied()),
                registrySnapshot(observation, projection)
        ));
    }

    /** Projects the one CONTROL signal the runtime produces. */
    ProjectedObservation replayExhausted() {
        PublishedObservation<ObservationPayload> observation =
                new PublishedObservation<>(
                        "control-" + (busSequence + 1),
                        ++busSequence,
                        SOURCE,
                        new FixtureSourcePosition(busSequence),
                        Optional.of(EPISODE_START),
                        EPISODE_START,
                        ObservationCaptureMode.REPLAY,
                        ObservationSourceSignal.SCHEMA_VERSION,
                        new ObservationSourceSignal(
                                ObservationSourceSignal
                                        .ObservationSourceSignalType
                                        .REPLAY_SOURCE_EXHAUSTED
                        )
                );
        CurrentGameStateProjection projection =
                projector.applyAndCapture(observation);
        BehaviorGraphApplyResult apply =
                BehaviorGraphApplyResult.disabled(
                        observation.busSequence()
                );
        return record(new ProjectedObservation(
                observation,
                projection.applied(),
                projection.changes(),
                apply,
                BehaviorSituationSnapshot.unavailable(
                        apply,
                        BehaviorSituationCaptureStatus.GRAPH_DISABLED
                ),
                semantics.create(observation, projection.applied()),
                registrySnapshot(observation, projection)
        ));
    }

    /** Everything recorded so far, drained through the final trigger. */
    SemanticEffectAccumulator.Drained drainThrough(long throughBusSequence) {
        return effects.drainThrough(throughBusSequence);
    }

    DecisionTurnInputs inputs(
            long turnSequence,
            List<ProjectedObservation> triggers,
            List<DeliveredModelComment> comments
    ) {
        return new DecisionTurnInputs(
                turnSequence,
                triggers,
                drainThrough(triggers.getLast().busSequence()),
                comments
        );
    }

    DecisionTurnInputs inputs(
            List<ProjectedObservation> triggers
    ) {
        return inputs(1L, triggers, List.of());
    }

    private ProjectedObservation record(ProjectedObservation projected) {
        effects.record(projected.semanticEnvelope());
        return projected;
    }

    private PublishedObservation<JournalEventObservation> publish(
            String rawJson
    ) {
        byte[] bytes = rawJson.strip().getBytes(StandardCharsets.UTF_8);
        ParsedJournalRecord parsed = (ParsedJournalRecord) parser.parse(
                new CompleteJournalRecord(
                        "Journal.decision-test.log",
                        sourceOffset,
                        bytes
                )
        );
        sourceOffset += bytes.length + 1L;
        ObservationDraft<JournalEventObservation> draft = adapter.adapt(
                parsed,
                ObservationCaptureMode.REPLAY,
                parsed.optionalJournalTimestamp().orElse(EPISODE_START)
        );
        return new PublishedObservation<>(
                draft.observationId(),
                ++busSequence,
                draft.source(),
                draft.sourcePosition(),
                draft.sourceTime(),
                draft.observedAt(),
                draft.captureMode(),
                draft.schemaVersion(),
                draft.payload()
        );
    }

    /**
     * @param cursorOccurrenceId identity for the last occurrence, or
     *                           {@code null} to keep the anonymous fixture
     *                           identity used by occurrences no observation in
     *                           this fixture claims to have committed
     */
    private static List<SituationOccurrence> occurrences(
            List<TrajectoryEntry> entries,
            EventOccurrenceId cursorOccurrenceId
    ) {
        List<SituationOccurrence> result = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            TrajectoryEntry entry = entries.get(index);
            boolean last = index == entries.size() - 1;
            result.add(new SituationOccurrence(
                    last && cursorOccurrenceId != null
                            ? cursorOccurrenceId
                            : new EventOccurrenceId("occurrence-" + index),
                    index,
                    entry.eventType(),
                    entry.source(),
                    EPISODE_START.plusSeconds(index),
                    last,
                    entry.body()
            ));
        }
        return List.copyOf(result);
    }

    /**
     * One scripted occurrence: normalized type, provenance and where it was.
     *
     * <p>The body is scripted rather than derived for the same reason the
     * provenance is: the graph records it from the context it accepted the
     * occurrence with, and a fixture that re-derived it would be asserting
     * against its own guess.</p>
     */
    record TrajectoryEntry(
            NormalizedEventType eventType,
            EventOccurrenceSource source,
            BodyIdentity body
    ) {

        static TrajectoryEntry journal(NormalizedEventType eventType) {
            return new TrajectoryEntry(
                    eventType,
                    EventOccurrenceSource.JOURNAL,
                    null
            );
        }

        static TrajectoryEntry status(NormalizedEventType eventType) {
            return new TrajectoryEntry(
                    eventType,
                    EventOccurrenceSource.STATUS,
                    null
            );
        }

        static TrajectoryEntry synthetic(NormalizedEventType eventType) {
            return new TrajectoryEntry(
                    eventType,
                    EventOccurrenceSource.SYNTHETIC,
                    null
            );
        }

        /** Provenance genuinely absent: restored from persistence. */
        static TrajectoryEntry legacy(NormalizedEventType eventType) {
            return new TrajectoryEntry(eventType, null, null);
        }

        /** The same occurrence, recorded at one identified body. */
        TrajectoryEntry at(long systemAddress, long bodyId) {
            return new TrajectoryEntry(
                    eventType,
                    source,
                    new BodyIdentity(
                            systemAddress,
                            bodyId
                    )
            );
        }
    }

    private static Map<NormalizedEventType, Long> counts(
            List<SituationOccurrence> occurrences
    ) {
        Map<NormalizedEventType, Long> counts = new TreeMap<>();
        occurrences.forEach(occurrence ->
                counts.merge(occurrence.eventType(), 1L, Long::sum));
        return counts;
    }

    private static List<SituationNextEventPrediction> predictions(
            NormalizedEventType source,
            List<NormalizedEventType> predicted,
            ContextKey contextKey
    ) {
        int count = predicted.size();
        if (count == 0) {
            return List.of();
        }
        boolean contextual = !ContextKey.EMPTY.equals(contextKey);
        double probability = 1.0 / count;
        List<SituationNextEventPrediction> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(new SituationNextEventPrediction(
                    source,
                    predicted.get(index),
                    probability,
                    contextual
                            ? PredictionBasis.CONTEXTUAL
                            : PredictionBasis.GLOBAL,
                    probability,
                    count - index,
                    contextual ? 1L : 0L,
                    contextual ? 2.5 : 0.0,
                    contextKey,
                    count - index
            ));
        }
        return List.copyOf(result);
    }

    /** A trajectory of {@code SYSTEM_ENTRY} plus generated distinct types. */
    static List<NormalizedEventType> generatedTrajectory(int size) {
        List<NormalizedEventType> types = new ArrayList<>(size);
        types.add(NormalizedEventType.SYSTEM_ENTRY);
        for (int index = 1; index < size; index++) {
            types.add(NormalizedEventType.of("EVENT_%02d".formatted(index)));
        }
        return List.copyOf(types);
    }

    private record FixtureSourcePosition(long sequence)
            implements SourcePosition {
    }
}
