package kairon.observer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.snapshot.BehaviorSituationCaptureStatus;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.config.KaironConfiguration.ObserverConfiguration;
import kairon.llm.LlmClient;
import kairon.llm.DecisionPromptFactory;
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
import kairon.observation.source.ObservationSourceSignal
        .ObservationSourceSignalType;
import kairon.observation.status.StatusSnapshotObservation;
import kairon.observer.decision.JacksonDecisionRequestSerializer;
import kairon.observer.decision.LlmDecisionRequestCompactor;
import kairon.observer.decision.LlmDecisionRequestFactory;
import kairon.observer.decision.DecisionTurnPolicy;
import kairon.output.CommentSink;
import kairon.projection.ProjectedObservation;
import kairon.projection.ProjectedObservationBus;
import kairon.projection.SemanticEnvelopeFactory;
import kairon.semantics.SemanticEffectAccumulator;
import kairon.semantics.SemanticObservationEnvelope;
import kairon.semantics.SemanticSourceRole;
import kairon.speech.SpeechSynthesisClient.SpeechFailureCategory;
import kairon.state.CurrentGameStateProjection;
import kairon.state.CurrentGameStateProjector;
import kairon.system.SystemRegistrySnapshot;
import kairon.trace.JsonLinesTurnTraceWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production wiring: every projection contributes its semantic effect through
 * the real subscriber, and only {@code NEW} journal events become triggers.
 */
class ObserverTurnCoordinatorSemanticEffectsTest {

    private static final String SILENT_RESPONSE =
            "{\"decision\":\"SILENT\"}";
    private static final ObservationSource SOURCE =
            new ObservationSource("elite-journal", "coordinator-test");
    private static final Instant TIME =
            Instant.parse("2026-07-30T14:00:00Z");

    @TempDir
    Path traceDirectory;

    @Test
    void newContextStatusControlAndNewAllReachTheAccumulatorInBusOrder()
            throws Exception {
        try (Pipeline pipeline = new Pipeline(coordinator())) {
            pipeline.journal("""
                    {"timestamp":"2026-07-30T14:00:00Z",\
                    "event":"ApproachBody","StarSystem":"Alpha",\
                    "SystemAddress":11,"Body":"Alpha 1","BodyID":3}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T14:00:01Z","event":"FSDTarget",\
                    "Name":"Beta","SystemAddress":12,\
                    "RemainingJumpsInRoute":1}
                    """);
            pipeline.status();
            pipeline.replayExhausted();
            pipeline.awaitIdle();

            // Replay exhaustion makes a turn eligible immediately, so the
            // first turn closes on trigger A and drains only through it.
            List<Long> firstTurn = busSequences(pipeline.drained());

            pipeline.journal("""
                    {"timestamp":"2026-07-30T14:00:05Z","event":"SupercruiseExit",\
                    "StarSystem":"Alpha","SystemAddress":11,\
                    "Body":"Alpha 1","BodyID":3,"BodyType":"Planet"}
                    """);
            pipeline.awaitIdle();
            List<Long> secondTurn = busSequences(pipeline.drained());

            List<Long> everything = new ArrayList<>(firstTurn);
            everything.addAll(secondTurn);

            assertEquals(
                    List.of(1L, 2L, 3L, 4L, 5L),
                    everything,
                    "every role must reach the accumulator in bus order"
            );
            assertEquals(
                    everything.size(),
                    everything.stream().distinct().count(),
                    "each effect must be recorded exactly once"
            );

            // The hidden roles are present as effects and absent as triggers.
            assertEquals(List.of(1L, 5L), pipeline.triggerBusSequences());
            assertFalse(secondTurn.isEmpty());
            assertTrue(secondTurn.containsAll(List.of(2L, 3L, 4L)));

            assertEquals(0, pipeline.pendingCount());
            assertTrue(pipeline.drained().suppression().isEmpty());
        }
    }

    @Test
    void effectsRecordedAfterTheFinalTriggerSurviveForTheNextTurn()
            throws Exception {
        try (Pipeline pipeline = new Pipeline(coordinator())) {
            pipeline.journal("""
                    {"timestamp":"2026-07-30T14:00:00Z",\
                    "event":"ApproachBody","StarSystem":"Alpha",\
                    "SystemAddress":11,"Body":"Alpha 1","BodyID":3}
                    """);
            pipeline.awaitIdle();

            pipeline.journal("""
                    {"timestamp":"2026-07-30T14:00:01Z","event":"Scan",\
                    "ScanType":"Detailed","SystemAddress":11,"BodyID":3,\
                    "BodyName":"Alpha 1","PlanetClass":"Rocky body"}
                    """);
            pipeline.awaitApplied();

            assertEquals(
                    1,
                    pipeline.pendingCount(),
                    "a later effect belongs to a later turn"
            );
            assertEquals(List.of(1L), busSequences(pipeline.drained()));
        }
    }

    @Test
    void aSilentTurnDoesNotReplayAlreadyDrainedEffects() throws Exception {
        try (Pipeline pipeline = new Pipeline(coordinator())) {
            pipeline.journal("""
                    {"timestamp":"2026-07-30T14:00:00Z",\
                    "event":"ApproachBody","StarSystem":"Alpha",\
                    "SystemAddress":11,"Body":"Alpha 1","BodyID":3}
                    """);
            pipeline.awaitIdle();
            assertEquals(List.of(1L), busSequences(pipeline.drained()));

            pipeline.journal("""
                    {"timestamp":"2026-07-30T14:00:05Z","event":"SupercruiseExit",\
                    "StarSystem":"Alpha","SystemAddress":11,\
                    "Body":"Alpha 1","BodyID":3,"BodyType":"Planet"}
                    """);
            pipeline.awaitIdle();

            assertEquals(
                    List.of(2L),
                    busSequences(pipeline.drained()),
                    "the second turn must not see the first turn's effects"
            );
            assertEquals(0, pipeline.pendingCount());
        }
    }

    @Test
    void diagnosticObservationWithoutStateChangeNeverEntersModelContext()
            throws Exception {
        try (Pipeline pipeline = new Pipeline(coordinator())) {
            // Music is catalogued, DIAGNOSTIC_ONLY, and changes no state.
            pipeline.journal("""
                    {"timestamp":"2026-07-30T14:00:00Z","event":"Music",\
                    "MusicTrack":"Exploration"}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T14:00:01Z",\
                    "event":"ApproachBody","StarSystem":"Alpha",\
                    "SystemAddress":11,"Body":"Alpha 1","BodyID":3}
                    """);
            pipeline.awaitIdle();

            assertEquals(
                    List.of(2L),
                    busSequences(pipeline.drained()),
                    "crossing the bus is not a reason to enter model context"
            );
            assertEquals(List.of(2L), pipeline.triggerBusSequences());
        }
    }

    @Test
    void subscriberShutdownDoesNotReorderQueuedEffects() throws Exception {
        Pipeline pipeline = new Pipeline(coordinator());
        pipeline.journal("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Scan",\
                "ScanType":"Detailed","SystemAddress":11,"BodyID":3,\
                "BodyName":"Alpha 1","PlanetClass":"Rocky body"}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T14:00:00Z",\
                "event":"ApproachBody","StarSystem":"Alpha",\
                "SystemAddress":11,"Body":"Alpha 1","BodyID":3}
                """);
        pipeline.closeSubscriptionOnly();
        pipeline.awaitIdle();

        assertEquals(List.of(1L, 2L), busSequences(pipeline.drained()));
        pipeline.close();
    }

    @Test
    void identicalReplayProducesIdenticalEffectOrdering() throws Exception {
        assertEquals(replayScript(), replayScript());
    }

    private List<String> replayScript() throws Exception {
        try (Pipeline pipeline = new Pipeline(coordinator())) {
            pipeline.journal("""
                    {"timestamp":"2026-07-30T14:00:00Z",\
                    "event":"ApproachBody","StarSystem":"Alpha",\
                    "SystemAddress":11,"Body":"Alpha 1","BodyID":3}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T14:00:01Z","event":"Scan",\
                    "ScanType":"Detailed","SystemAddress":11,"BodyID":3,\
                    "BodyName":"Alpha 1","PlanetClass":"Rocky body"}
                    """);
            pipeline.status();
            pipeline.journal("""
                    {"timestamp":"2026-07-30T14:00:05Z","event":"SupercruiseExit",\
                    "StarSystem":"Alpha","SystemAddress":11,\
                    "Body":"Alpha 1","BodyID":3,"BodyType":"Planet"}
                    """);
            pipeline.awaitIdle();
            return pipeline.drained().envelopes().stream()
                    .map(envelope -> envelope.busSequence()
                            + ":" + envelope.sourceRole()
                            + ":" + envelope.rawObservationType())
                    .toList();
        }
    }

    private static List<Long> busSequences(
            SemanticEffectAccumulator.Drained drained
    ) {
        List<Long> sequences = new ArrayList<>();
        drained.envelopes().forEach(
                envelope -> sequences.add(envelope.busSequence())
        );
        return sequences;
    }

    private ObserverTurnCoordinator coordinator() {
        Path trace = traceDirectory.resolve("coordinator-semantics.jsonl");
        return new ObserverTurnCoordinator(
                new ObserverConfiguration("en", 5L, 10L, trace),
                new LlmDecisionRequestCompactor(
                new LlmDecisionRequestFactory(),
                new JacksonDecisionRequestSerializer(),
                DecisionTurnPolicy.production()),
                new DecisionPromptFactory(),
                new SilentLlmClient(),
                new NoOpCommentSink(),
                new JsonLinesTurnTraceWriter(trace)
        );
    }

    /**
     * The real projection-to-observer path: production subscriber, production
     * bus, production coordinator.
     */
    private static final class Pipeline implements AutoCloseable {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final JournalLineParser parser = new JournalLineParser();
        private final JournalObservationAdapter adapter =
                new JournalObservationAdapter(SOURCE);
        private final CurrentGameStateProjector projector =
                new CurrentGameStateProjector();
        private final ProjectedObservationBus bus =
                new ProjectedObservationBus();
        private final ObserverTurnCoordinator coordinator;
        private final LlmJournalObserverSubscriber.Subscriptions
                subscriptions;
        private final List<Long> triggerBusSequences = new ArrayList<>();

        private long sourceOffset;
        private long busSequence;
        private boolean subscriptionClosed;

        private Pipeline(ObserverTurnCoordinator coordinator) {
            this.coordinator = coordinator;
            this.subscriptions =
                    new LlmJournalObserverSubscriber(coordinator)
                            .subscribeTo(bus);
        }

        private void journal(String rawJson) {
            byte[] bytes = rawJson.strip().getBytes(StandardCharsets.UTF_8);
            ParsedJournalRecord parsed = assertInstanceOf(
                    ParsedJournalRecord.class,
                    parser.parse(new CompleteJournalRecord(
                            "Journal.pipeline-test.log",
                            sourceOffset,
                            bytes
                    ))
            );
            sourceOffset += bytes.length + 1L;
            var draft = adapter.adapt(
                    parsed,
                    ObservationCaptureMode.REPLAY,
                    parsed.optionalJournalTimestamp().orElse(TIME)
            );
            publish(new PublishedObservation<JournalEventObservation>(
                    draft.observationId(),
                    ++busSequence,
                    draft.source(),
                    draft.sourcePosition(),
                    draft.sourceTime(),
                    draft.observedAt(),
                    draft.captureMode(),
                    draft.schemaVersion(),
                    draft.payload()
            ));
        }

        private void status() throws Exception {
            String rawJson =
                    "{\"timestamp\":\"2026-07-30T14:00:02Z\",\"Flags\":16}";
            publish(new PublishedObservation<ObservationPayload>(
                    "status-" + (busSequence + 1),
                    ++busSequence,
                    SOURCE,
                    new TestSourcePosition(busSequence),
                    Optional.of(TIME),
                    TIME,
                    ObservationCaptureMode.REPLAY,
                    StatusSnapshotObservation.SCHEMA_VERSION,
                    new StatusSnapshotObservation(
                            rawJson,
                            MAPPER.readTree(rawJson),
                            Optional.of(TIME),
                            OptionalLong.of(16L),
                            OptionalLong.empty(),
                            OptionalInt.empty()
                    )
            ));
        }

        private void replayExhausted() {
            publish(new PublishedObservation<ObservationPayload>(
                    "control-" + (busSequence + 1),
                    ++busSequence,
                    SOURCE,
                    new TestSourcePosition(busSequence),
                    Optional.of(TIME),
                    TIME,
                    ObservationCaptureMode.REPLAY,
                    ObservationSourceSignal.SCHEMA_VERSION,
                    new ObservationSourceSignal(
                            ObservationSourceSignalType
                                    .REPLAY_SOURCE_EXHAUSTED
                    )
            ));
        }

        private void publish(PublishedObservation<?> observation) {
            CurrentGameStateProjection state =
                    projector.applyAndCapture(observation);
            BehaviorGraphApplyResult graph =
                    BehaviorGraphApplyResult.disabled(
                            observation.busSequence()
                    );
            ProjectedObservation projected = new ProjectedObservation(
                    observation,
                    state.applied(),
                    state.changes(),
                    graph,
                    BehaviorSituationSnapshot.unavailable(
                            graph,
                            BehaviorSituationCaptureStatus.GRAPH_DISABLED
                    ),
                    SemanticEnvelopeFactory.production().create(
                            observation,
                            state.applied()
                    ),
                    SystemRegistrySnapshot.empty(observation.busSequence())
            );
            if (projected.semanticEnvelope().sourceRole()
                    == SemanticSourceRole.NEW) {
                triggerBusSequences.add(observation.busSequence());
            }
            bus.publish(projected);
        }

        private List<Long> triggerBusSequences() {
            return List.copyOf(triggerBusSequences);
        }

        private void awaitIdle() throws Exception {
            coordinator.awaitIdle().toCompletableFuture().get();
        }

        private void awaitApplied() throws Exception {
            coordinator.awaitApplied().toCompletableFuture().get();
        }

        private SemanticEffectAccumulator.Drained drained() throws Exception {
            return coordinator.lastDrainedSemanticEffects()
                    .toCompletableFuture()
                    .get();
        }

        private int pendingCount() throws Exception {
            return coordinator.pendingSemanticEffectCount()
                    .toCompletableFuture()
                    .get();
        }

        private void closeSubscriptionOnly() {
            subscriptions.close();
            subscriptionClosed = true;
            assertFalse(subscriptions.allActive());
        }

        @Override
        public void close() {
            if (!subscriptionClosed) {
                subscriptions.close();
            }
            bus.close();
            coordinator.close();
        }
    }

    private record TestSourcePosition(long sequence)
            implements SourcePosition {
    }

    private static final class SilentLlmClient implements LlmClient {

        @Override
        public CompletionStage<LlmResponse> complete(
                ModelInput exactModelInput
        ) {
            return CompletableFuture.completedFuture(
                    new LlmResponse(SILENT_RESPONSE, 1L)
            );
        }

        @Override
        public ProviderDescriptor provider() {
            return new ProviderDescriptor(
                    "test",
                    "LM_STUDIO",
                    URI.create("http://localhost:1234/v1"),
                    "test-model"
            );
        }
    }

    private static final class NoOpCommentSink implements CommentSink {

        @Override
        public CompletionStage<CommentDeliveryResult> deliver(
                String comment
        ) {
            return CompletableFuture.completedFuture(
                    new CommentDeliveryResult(
                            speechDescriptor(),
                            ConsoleOutcome.DELIVERED,
                            new SpeechDeliveryResult(
                                    SpeechOutcome.DISABLED,
                                    SpeechFailureCategory.NONE,
                                    null,
                                    null,
                                    null,
                                    null
                            )
                    )
            );
        }

        @Override
        public SpeechDescriptor speechDescriptor() {
            return SpeechDescriptor.disabled("none", "none");
        }
    }
}
