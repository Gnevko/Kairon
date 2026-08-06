package kairon.observer.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.bus.BehaviorGraphObservationProcessor;
import kairon.behavior.context.BodyDetail;
import kairon.behavior.graph.BehaviorGraphQueryService;
import kairon.behavior.graph.BehaviorGraphService;
import kairon.behavior.model.EdgeKey;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventTypeNode;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.TransitionEdge;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.behavior.persistence.InMemoryBehaviorGraphStore;
import kairon.config.KaironConfiguration.BehaviorGraphConfiguration;
import kairon.config.KaironConfiguration.ObserverConfiguration;
import kairon.llm.LlmClient;
import kairon.llm.DecisionPromptFactory;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.bus.InProcessObservationBus;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.observation.journal.JournalObservationAdapter
        .JournalSourcePosition;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.source.ObservationSourceSignal
        .ObservationSourceSignalType;
import kairon.observation.status.StatusObservationAdapter;
import kairon.observation.status.StatusSnapshotObservation;
import kairon.observer.LlmJournalObserverSubscriber;
import kairon.observer.ObserverTurnCoordinator;
import kairon.observer.ObserverTurnListener;
import kairon.output.CommentSink;
import kairon.projection.ObservationProjectionCoordinator;
import kairon.projection.ProjectedObservation;
import kairon.projection.ObservationProjectionSubscriber;
import kairon.projection.ProjectedObservationBus;
import kairon.projection.RegistryBodyDetail;
import kairon.semantics.SemanticEffectAccumulator;
import kairon.semantics.SemanticSourceRole;
import kairon.speech.SpeechSynthesisClient.SpeechFailureCategory;
import kairon.state.CurrentGameStateProjector;
import kairon.trace.JsonLinesTurnTraceWriter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The real production path, end to end.
 *
 * <p>Real observation bus, real projection coordinator, real behavior graph,
 * real semantic envelope, real observer subscriber, real turn coordinator,
 * real compactor and prompt. Only the provider and the speech sink are
 * stubbed, and neither can influence what the model is sent.</p>
 */
final class DecisionProductionPipeline implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObservationSource SOURCE =
            new ObservationSource("elite-journal", "decision-pipeline");
    private static final String SILENT = "{\"decision\":\"SILENT\"}";

    private final InProcessObservationBus rawBus =
            new InProcessObservationBus();
    private final ProjectedObservationBus projectedBus =
            new ProjectedObservationBus();
    private final InMemoryBehaviorGraphStore store =
            new InMemoryBehaviorGraphStore();
    private final JournalLineParser parser = new JournalLineParser();
    private final JournalObservationAdapter journalAdapter =
            new JournalObservationAdapter(SOURCE);
    private final StatusObservationAdapter statusAdapter =
            new StatusObservationAdapter(SOURCE, "Status.json");
    private final BehaviorGraphService graph;
    private final ObservationProjectionCoordinator projection;
    private final ObservationProjectionSubscriber.Subscription
            projectionSubscription;
    private final ObserverTurnCoordinator observer;
    private final LlmJournalObserverSubscriber.Subscriptions
            observerSubscription;
    private final RecordingLlmClient llm = new RecordingLlmClient();
    private final RecordingTurnListener listener =
            new RecordingTurnListener();
    private final SemanticEffectAccumulator capturedEffects =
            new SemanticEffectAccumulator();
    private final List<ProjectedObservation> capturedTriggers =
            new CopyOnWriteArrayList<>();
    private final List<ProjectedObservation> capturedProjections =
            new CopyOnWriteArrayList<>();
    private final ProjectedObservationBus.Subscription
            captureSubscription;

    private long journalOffset;
    private long statusSequence;
    private boolean closed;

    /**
     * How one pipeline instance is wired.
     *
     * <p>Additive: {@link #production()} reproduces exactly what the two
     * original constructors built, so every existing test is unaffected. The
     * options exist for the cross-layer contract harness, which needs a
     * graph-free composition and a batch boundary it can reach without ending
     * the replay.</p>
     *
     * @param graphEnabled       whether a behaviour graph is wired at all
     * @param turnPolicy         trigger cap and character budget
     * @param quietPeriodMs      how long after the last trigger a batch closes
     * @param maximumBatchAgeMs  how long after the first trigger it closes
     */
    record Options(
            boolean graphEnabled,
            DecisionTurnPolicy turnPolicy,
            long quietPeriodMs,
            long maximumBatchAgeMs
    ) {

        static Options production() {
            return new Options(
                    true,
                    DecisionTurnPolicy.production(),
                    60_000L,
                    120_000L
            );
        }

        Options withGraphEnabled(boolean enabled) {
            return new Options(
                    enabled,
                    turnPolicy,
                    quietPeriodMs,
                    maximumBatchAgeMs
            );
        }

        Options withQuietPeriodMs(long quietPeriod) {
            return new Options(
                    graphEnabled,
                    turnPolicy,
                    quietPeriod,
                    maximumBatchAgeMs
            );
        }
    }

    DecisionProductionPipeline(Path directory) {
        this(directory, DecisionTurnPolicy.production());
    }

    DecisionProductionPipeline(Path directory, DecisionTurnPolicy policy) {
        this(
                directory,
                new Options(true, policy, 60_000L, 120_000L)
        );
    }

    DecisionProductionPipeline(Path directory, Options options) {
        DecisionTurnPolicy policy = options.turnPolicy();
        graph = options.graphEnabled()
                ? new BehaviorGraphService(
                        new BehaviorGraphConfiguration(
                                true,
                                directory.resolve("graphs"),
                                Duration.ofDays(30),
                                2.0,
                                50,
                                false
                        ),
                        store
                )
                : null;
        projection = new ObservationProjectionCoordinator(
                new CurrentGameStateProjector(),
                graph == null
                        ? Optional.empty()
                        : Optional.of(
                                new BehaviorGraphObservationProcessor(graph)
                        ),
                graph == null
                        ? Optional.empty()
                        : Optional.of(new BehaviorGraphQueryService(graph)),
                projectedBus
        );
        projectionSubscription =
                new ObservationProjectionSubscriber(projection)
                        .subscribeTo(rawBus);
        observer = new ObserverTurnCoordinator(
                new ObserverConfiguration(
                        "en",
                        options.quietPeriodMs(),
                        options.maximumBatchAgeMs(),
                        directory.resolve("decision-pipeline-turns.jsonl")
                ),
                new LlmDecisionRequestCompactor(
                        new LlmDecisionRequestFactory(),
                        new JacksonDecisionRequestSerializer(),
                        policy
                ),
                new DecisionPromptFactory(),
                llm,
                new SilentCommentSink(),
                new JsonLinesTurnTraceWriter(
                        directory.resolve("decision-pipeline-turns.jsonl")
                ),
                listener
        );
        observerSubscription = new LlmJournalObserverSubscriber(observer)
                .subscribeTo(projectedBus);
        captureSubscription = projectedBus.subscribe(
                "decision-pipeline-capture",
                this::capture
        );
    }

    /**
     * The turn inputs the coordinator saw, reassembled from the same bus.
     *
     * <p>Parsing, projection, the behavior graph and the semantic envelope are
     * all production. Only the batch boundary is reproduced here rather than
     * observed, which is why {@code assertMatchesSentDocument} exists: it
     * compares these trigger bus sequences with the ones in the document the
     * provider actually received.</p>
     */
    DecisionTurnInputs capturedInputs() {
        if (capturedTriggers.isEmpty()) {
            throw new IllegalStateException("no NEW trigger was captured");
        }
        List<ProjectedObservation> triggers = List.copyOf(capturedTriggers);
        return new DecisionTurnInputs(
                1L,
                triggers,
                capturedEffects.drainThrough(
                        triggers.getLast().busSequence()
                ),
                List.of()
        );
    }

    /** Every NEW trigger the bus carried, in order. */
    List<ProjectedObservation> capturedTriggers() {
        return List.copyOf(capturedTriggers);
    }

    /**
     * Turn inputs for a chosen subset of what the bus carried.
     *
     * <p>For cases that need many observations to have reached the real graph
     * but only some of them to be this turn's triggers. Everything in the inputs
     * is still production output — only which of them the batch closed over is
     * decided here.</p>
     */
    DecisionTurnInputs inputsFor(List<ProjectedObservation> triggers) {
        return new DecisionTurnInputs(
                1L,
                triggers,
                capturedEffects.drainThrough(
                        triggers.getLast().busSequence()
                ),
                List.of()
        );
    }

    /**
     * Every projection the bus carried, trigger or not.
     *
     * <p>For state a non-trigger established: a cargo snapshot never opens a
     * turn, and what it did to canonical state can only be read from its own
     * projection.</p>
     */
    List<ProjectedObservation> capturedProjections() {
        return List.copyOf(capturedProjections);
    }

    /**
     * What the current-system registry has established about one body.
     *
     * <p>Read through the production translation, so what a test asserts is
     * the shape a consumer receives rather than the registry's own types. A
     * body in another system, or one nothing has recorded, answers with
     * everything null.</p>
     */
    BodyDetail establishedBody(long systemAddress, long bodyId) {
        return new RegistryBodyDetail(
                capturedProjections.getLast().systemRegistry()
        ).detailOf(systemAddress, bodyId);
    }

    private void capture(ProjectedObservation projected) {
        capturedProjections.add(projected);
        capturedEffects.record(projected.semanticEnvelope());
        if (projected.semanticEnvelope().sourceRole()
                == SemanticSourceRole.NEW
                && projected.trigger().captureMode()
                != ObservationCaptureMode.BOOTSTRAP) {
            capturedTriggers.add(projected);
        }
    }

    void journal(String rawJson) {
        journal(ObservationCaptureMode.REPLAY, rawJson);
    }

    void journal(ObservationCaptureMode mode, String rawJson) {
        byte[] bytes = rawJson.strip().getBytes(StandardCharsets.UTF_8);
        ParsedJournalRecord parsed = (ParsedJournalRecord) parser.parse(
                new CompleteJournalRecord(
                        "Journal.decision-pipeline.log",
                        journalOffset,
                        bytes
                )
        );
        journalOffset += bytes.length + 1L;
        publish(journalAdapter.adapt(
                parsed,
                mode,
                parsed.optionalJournalTimestamp().orElseThrow()
        ));
    }

    /** Publishes a Status snapshot: a hidden source with real graph effects. */
    void status(String timestamp, long flags, int guiFocus) {
        status(ObservationCaptureMode.REPLAY, timestamp, flags, guiFocus);
    }

    void status(
            ObservationCaptureMode mode,
            String timestamp,
            long flags,
            int guiFocus
    ) {
        String rawJson = "{\"timestamp\":\"" + timestamp
                + "\",\"Flags\":" + flags
                + ",\"GuiFocus\":" + guiFocus + "}";
        try {
            StatusSnapshotObservation snapshot = new StatusSnapshotObservation(
                    rawJson,
                    JSON.readTree(rawJson),
                    Optional.of(Instant.parse(timestamp)),
                    OptionalLong.of(flags),
                    OptionalLong.empty(),
                    OptionalInt.of(guiFocus)
            );
            publish(statusAdapter.adapt(
                    snapshot,
                    statusSequence++,
                    mode,
                    Instant.parse(timestamp)
            ));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    void replayExhausted(String timestamp) {
        publish(new ObservationDraft<>(
                "replay-source-exhausted",
                SOURCE,
                new JournalSourcePosition(
                        "Journal.decision-pipeline.log",
                        journalOffset + 1
                ),
                Optional.empty(),
                Instant.parse(timestamp),
                ObservationCaptureMode.REPLAY,
                ObservationSourceSignal.SCHEMA_VERSION,
                new ObservationSourceSignal(
                        ObservationSourceSignalType.REPLAY_SOURCE_EXHAUSTED
                )
        ));
    }

    void settle() throws Exception {
        projection.awaitIdle().toCompletableFuture().get(5, TimeUnit.SECONDS);
        observer.awaitIdle().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /**
     * Waits for the projection alone, leaving the observer batch open.
     *
     * <p>For cases that need the real graph to advance but never need a turn
     * to fire. Waiting on the observer here would block until the configured
     * quiet period expires.</p>
     */
    void settleProjection() throws Exception {
        projection.awaitIdle().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /** Every model input the provider actually received. */
    List<LlmClient.ModelInput> modelInputs() {
        return List.copyOf(llm.inputs);
    }

    /**
     * Every event the provider was actually shown, in order, across all turns.
     *
     * <p>The only honest answer to "did this open a turn": the capture
     * subscriber above sees every {@code NEW} projection, while admission and
     * the observer's own novelty memory decide which of them the model is
     * given.</p>
     */
    List<String> modelFacingKinds() {
        java.util.Map<String, String> kindByDescription = kindByDescription();
        List<String> kinds = new java.util.ArrayList<>();
        for (String description : modelFacingDescriptions()) {
            String kind = kindByDescription.get(description);
            if (kind == null) {
                throw new IllegalStateException(
                        "no observed event describes itself as: " + description
                );
            }
            kinds.add(kind);
        }
        return List.copyOf(kinds);
    }

    /** Every literal description the provider was actually shown, in order. */
    List<String> modelFacingDescriptions() {
        List<String> descriptions = new java.util.ArrayList<>();
        for (LlmClient.ModelInput input : llm.inputs) {
            String userMessage = input.userMessage();
            int start = userMessage.indexOf('{');
            try {
                JSON.readTree(userMessage.substring(start))
                        .path("events")
                        .forEach(event -> descriptions.add(
                                event.path("event").textValue()
                        ));
            } catch (Exception failure) {
                throw new IllegalStateException(userMessage, failure);
            }
        }
        return List.copyOf(descriptions);
    }

    /**
     * Kairon's internal name for each description that was actually observed.
     *
     * <p>Built from the observations this run produced, by asking each payload
     * the two questions production asks it — what does it call itself, and what
     * rule does the catalogue give it. No table of descriptions exists anywhere,
     * and nothing is reversed from a name: a description reaches this map only
     * because an instance produced it.</p>
     */
    java.util.Map<String, String> kindByDescription() {
        java.util.Map<String, String> byDescription =
                new java.util.LinkedHashMap<>();
        for (ProjectedObservation projected : capturedProjections) {
            if (!(projected.trigger().payload()
                    instanceof LlmPresentableJournalEvent presentable)) {
                continue;
            }
            DecisionEventRule rule = DecisionEventCatalog.ruleFor(presentable);
            if (rule == null) {
                continue;
            }
            byDescription.putIfAbsent(
                    presentable.modelFacingDescription(),
                    rule.kind()
            );
        }
        return byDescription;
    }

    ObserverTurnCoordinator observer() {
        return observer;
    }

    /** Every decision the observer resolved, in order. */
    List<ObserverTurnListener.DecisionResolved> decisions() {
        return List.copyOf(listener.decisions);
    }

    /** Comments the sink was actually asked to deliver. */
    List<String> deliveredComments() {
        return List.copyOf(delivered);
    }

    /** The real graph the projection wrote to, for internal assertions. */
    BehaviorGraphService graph() {
        return graph;
    }

    /** Whether a behaviour graph is wired into this composition at all. */
    boolean graphEnabled() {
        return graph != null;
    }

    /** The graph this session identified, once a ship is known. */
    GraphId graphId() {
        return graph.currentGraphId().orElseThrow();
    }

    /** The graph identity, or empty when there is none to have. */
    Optional<GraphId> optionalGraphId() {
        return graph == null ? Optional.empty() : graph.currentGraphId();
    }

    /** The visit currently open on this graph, if one is. */
    SystemEpisode activeEpisode() {
        return graph.activeEpisode(graphId()).orElseThrow();
    }

    /** Every episode this graph holds, oldest first. */
    List<SystemEpisode> episodes() {
        return graph.episodes(graphId());
    }

    /** Where the graph is standing, or empty before anything is recorded. */
    Optional<GraphCursor> cursor() {
        return graph.cursor(graphId());
    }

    /** The structural types of the open visit, in order. */
    List<NormalizedEventType> episodeTypes() {
        return activeEpisode().timeline().stream()
                .map(EventOccurrence::eventType)
                .toList();
    }

    /** One learned transition, or null when the graph never saw it. */
    TransitionEdge edge(NormalizedEventType from, NormalizedEventType to) {
        return graph.graph(graphId()).orElseThrow()
                .edge(new EdgeKey(from, to));
    }

    /** How often the graph has recorded one structural type, all episodes. */
    long graphOccurrenceCount(NormalizedEventType eventType) {
        return graph.graph(graphId()).orElseThrow().nodes().stream()
                .filter(node -> node.eventType().equals(eventType))
                .mapToLong(EventTypeNode::rawOccurrenceCount)
                .findFirst()
                .orElse(0L);
    }

    private void publish(ObservationDraft<?> draft) {
        rawBus.publish(draft).toCompletableFuture().join();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        rawBus.drainAndClose().toCompletableFuture().join();
        projection.shutdown().toCompletableFuture().join();
        observer.shutdown().toCompletableFuture().join();
        observerSubscription.close();
        captureSubscription.close();
        projectionSubscription.close();
        projectedBus.close();
        store.close();
    }

    /**
     * Which observations the coordinator put into which turn.
     *
     * <p>Read from the production listener port rather than reconstructed:
     * {@code NEW_IN_FLIGHT} is emitted for every trigger the coordinator took
     * into a turn, and it carries both the bus sequence and the turn it was
     * bound to. Nothing else can say that without guessing.</p>
     */
    List<ObserverTurnListener.ObservationEffectChanged> observationEffects() {
        return List.copyOf(listener.effects);
    }

    private static final class RecordingTurnListener
            implements ObserverTurnListener {

        private final List<DecisionResolved> decisions =
                new CopyOnWriteArrayList<>();
        private final List<ObservationEffectChanged> effects =
                new CopyOnWriteArrayList<>();

        @Override
        public void onDecisionResolved(DecisionResolved decision) {
            decisions.add(decision);
        }

        @Override
        public void onObservationEffectChanged(
                ObservationEffectChanged change
        ) {
            effects.add(change);
        }
    }

    /** Makes the provider answer with an exact raw response next time. */
    void respondWith(String rawResponse) {
        llm.response.set(rawResponse);
    }

    /** Makes the provider fail, to exercise the model-failure outcome. */
    void failWith(RuntimeException failure) {
        llm.failure.set(failure);
    }

    private static final class RecordingLlmClient implements LlmClient {

        private final List<ModelInput> inputs = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicReference<String>
                response = new java.util.concurrent.atomic.AtomicReference<>(
                        SILENT
                );
        private final java.util.concurrent.atomic.AtomicReference<
                RuntimeException> failure =
                new java.util.concurrent.atomic.AtomicReference<>();

        @Override
        public CompletionStage<LlmResponse> complete(ModelInput input) {
            inputs.add(input);
            RuntimeException scheduled = failure.get();
            if (scheduled != null) {
                return CompletableFuture.failedFuture(scheduled);
            }
            return CompletableFuture.completedFuture(
                    new LlmResponse(response.get(), 1L)
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

    private final List<String> delivered = new CopyOnWriteArrayList<>();

    private final class SilentCommentSink implements CommentSink {

        @Override
        public CompletionStage<CommentDeliveryResult> deliver(String comment) {
            delivered.add(comment);
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
