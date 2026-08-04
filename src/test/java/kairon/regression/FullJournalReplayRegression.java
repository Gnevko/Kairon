package kairon.regression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kairon.behavior.bus.BehaviorGraphObservationProcessor;
import kairon.behavior.graph.BehaviorGraphQueryService;
import kairon.behavior.graph.BehaviorGraphService;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.OccurrenceTransition;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.TransitionEdge;
import kairon.behavior.persistence.BehaviorGraphStore;
import kairon.behavior.persistence.JsonBehaviorGraphStore;
import kairon.config.KaironConfiguration.BehaviorGraphConfiguration;
import kairon.config.KaironConfiguration.ObserverConfiguration;
import kairon.llm.DecisionPromptFactory;
import kairon.llm.LlmClient;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.bus.InProcessObservationBus;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.observation.journal.JournalReplaySource;
import kairon.observer.LlmJournalObserverSubscriber;
import kairon.observer.ObserverTurnCoordinator;
import kairon.observer.ObserverTurnListener;
import kairon.observer.decision.JacksonDecisionRequestSerializer;
import kairon.observer.decision.LlmDecisionRequestCompactor;
import kairon.observer.decision.LlmDecisionRequestFactory;
import kairon.observer.decision.DecisionTurnPolicy;
import kairon.output.CommentSink;
import kairon.projection.ObservationProjectionCoordinator;
import kairon.projection.ObservationProjectionSubscriber;
import kairon.projection.ProjectedObservation;
import kairon.projection.ProjectedObservationBus;
import kairon.speech.SpeechSynthesisClient.SpeechFailureCategory;
import kairon.state.CurrentGameStateProjector;
import kairon.trace.JsonLinesTurnTraceWriter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One full journal, replayed through the production path with the provider
 * and the speech sink replaced.
 *
 * <p>Everything between the file and the serialized {@code userMessage} is
 * production: the real {@link JournalReplaySource} with its journal-time pacing,
 * the real bus, the real projection coordinator, the real behaviour graph and
 * its JSON store, the real observer subscriber and turn coordinator with the
 * configured quiet period and batch age, the real request factory, compactor,
 * serializer and prompt.</p>
 *
 * <p>Three things are substituted, and none of them can change what a request
 * says: the provider is a recorder that answers a valid {@code SILENT} without
 * touching the network, the comment sink is a counter that neither prints nor
 * speaks, and the graph store and turn trace are written under {@code target/}
 * so the Commander's own graph is untouched.</p>
 *
 * <p>Not a JUnit test. The regression test drives it, and the class stays free
 * of assertions so the same run can be reported on in more than one way.</p>
 */
public final class FullJournalReplayRegression implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The response every turn gets. Valid, and never a comment. */
    private static final String SILENT = "{\"decision\":\"SILENT\"}";

    private final InProcessObservationBus bus = new InProcessObservationBus();
    private final ProjectedObservationBus projectedBus =
            new ProjectedObservationBus();
    private final BehaviorGraphStore store;
    private final BehaviorGraphService graph;
    private final ObservationProjectionCoordinator projection;
    private final ObservationProjectionSubscriber.Subscription
            projectionSubscription;
    private final ObserverTurnCoordinator observer;
    private final LlmJournalObserverSubscriber.Subscriptions
            observerSubscription;
    private final RecordingProvider provider;
    private final CountingSink sink = new CountingSink();
    private final RecordingListener listener = new RecordingListener();
    private final List<ProjectedObservation> projections =
            new CopyOnWriteArrayList<>();
    private final ProjectedObservationBus.Subscription captureSubscription;
    private final Path workingDirectory;

    private int completeJournalRecords;
    private boolean closed;

    /**
     * @param workingDirectory a directory under {@code target/}; the graph
     *                         store and the turn trace are written inside it
     * @param providerLatency  how long the substituted provider takes to
     *                         answer. Zero is the plain case; a supplier of
     *                         recorded latencies exists because batch
     *                         boundaries are decided on wall-clock time, so a
     *                         provider that answers instantly does not
     *                         reproduce the batches of a run whose provider did
     *                         not
     */
    public FullJournalReplayRegression(
            Path workingDirectory,
            ObserverConfiguration observerConfiguration,
            BehaviorGraphConfiguration graphConfiguration,
            ProviderLatency providerLatency
    ) {
        this.workingDirectory = Objects.requireNonNull(
                workingDirectory,
                "workingDirectory"
        );
        this.provider = new RecordingProvider(
                Objects.requireNonNull(providerLatency, "providerLatency")
        );
        store = new JsonBehaviorGraphStore(
                graphConfiguration.storageDirectory()
        );
        graph = new BehaviorGraphService(graphConfiguration, store);
        projection = new ObservationProjectionCoordinator(
                new CurrentGameStateProjector(),
                Optional.of(new BehaviorGraphObservationProcessor(graph)),
                Optional.of(new BehaviorGraphQueryService(graph)),
                projectedBus
        );
        projectionSubscription =
                new ObservationProjectionSubscriber(projection)
                        .subscribeTo(bus);
        observer = new ObserverTurnCoordinator(
                observerConfiguration,
                new LlmDecisionRequestCompactor(
                        new LlmDecisionRequestFactory(),
                        new JacksonDecisionRequestSerializer(),
                        DecisionTurnPolicy.production()
                ),
                new DecisionPromptFactory(),
                provider,
                sink,
                new JsonLinesTurnTraceWriter(observerConfiguration.traceFile()),
                listener
        );
        observerSubscription = new LlmJournalObserverSubscriber(observer)
                .subscribeTo(projectedBus);
        captureSubscription = projectedBus.subscribe(
                "full-replay-capture",
                projections::add
        );
    }

    /** How long the substituted provider takes for turn {@code n}, 1-based. */
    @FunctionalInterface
    public interface ProviderLatency {

        Duration forTurn(int oneBasedTurn);

        /** Answers as fast as the executor allows. */
        static ProviderLatency instant() {
            return turn -> Duration.ZERO;
        }

        /**
         * The latency the original run's provider actually took.
         *
         * <p>Not a way to make a comparison pass: batch boundaries are decided
         * on wall-clock arrival times, so a provider that answers in zero
         * milliseconds where the original took a second changes which triggers
         * end up in which batch. Reproducing the recorded latency removes the
         * one variable the substitution introduces, and leaves every production
         * timing rule exactly as configured.</p>
         */
        static ProviderLatency recorded(List<Long> millisPerTurn) {
            List<Long> copy = List.copyOf(millisPerTurn);
            return turn -> turn >= 1 && turn <= copy.size()
                    ? Duration.ofMillis(copy.get(turn - 1))
                    : Duration.ZERO;
        }
    }

    /** Replays the whole journal and waits for everything it started. */
    public void replay(Path journalFile) throws Exception {
        try (JournalReplaySource source = new JournalReplaySource(
                journalFile,
                new JournalLineParser(),
                new JournalObservationAdapter(new ObservationSource(
                        "elite-dangerous-journal",
                        "full-replay-regression"
                )),
                bus
        )) {
            JournalReplaySource.ReplayReport report =
                    source.publishAll().toCompletableFuture().join();
            completeJournalRecords = report.completeRecordCount();
            if (!report.successful()) {
                throw new IllegalStateException(
                        "replay did not complete: " + report.failure()
                );
            }
        }
        projection.awaitIdle().toCompletableFuture()
                .get(120, TimeUnit.SECONDS);
        observer.awaitIdle().toCompletableFuture()
                .get(120, TimeUnit.SECONDS);
    }

    public int completeJournalRecords() {
        return completeJournalRecords;
    }

    public int projectedObservations() {
        return projections.size();
    }

    public int providerCalls() {
        return provider.inputs.size();
    }

    public int networkCalls() {
        return provider.networkCalls.get();
    }

    public int deliveredComments() {
        return sink.delivered.get();
    }

    public int speechInvocations() {
        return sink.speechInvocations.get();
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * The model-facing turns and the graph, normalized.
     *
     * <p>Nothing here is a runtime identity: no timestamps, no observation ids,
     * no occurrence ids, no file paths. Occurrences are identified by the
     * journal record that minted them — their source sequence — which is a
     * stable property of the input rather than of the run.</p>
     */
    public ObjectNode normalized() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode turns = root.putArray("turns");
        Map<Long, List<Long>> triggersByTurn = listener.triggersByTurn();
        List<Long> turnOrder = listener.turnOrder();
        for (int index = 0; index < provider.inputs.size(); index++) {
            ObjectNode turn = turns.addObject();
            ArrayNode triggers = turn.putArray("triggerBusSequences");
            List<Long> bound = index < turnOrder.size()
                    ? triggersByTurn.getOrDefault(
                            turnOrder.get(index),
                            List.of()
                    )
                    : List.of();
            bound.forEach(triggers::add);
            turn.set("userMessage", parsedDocument(
                    provider.inputs.get(index).userMessage()
            ));
        }
        root.set("graph", normalizedGraph());
        return root;
    }

    /** Writes {@link #normalized()} with a stable pretty printer. */
    public void writeNormalized(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(normalized()) + "\n",
                StandardCharsets.UTF_8
        );
    }

    /**
     * The request document the provider was given.
     *
     * <p>The prompt wraps it in instructions; the document itself starts at the
     * first brace and is reused byte for byte from the serializer, so parsing
     * it back is a view rather than a re-serialization.</p>
     */
    private static JsonNode parsedDocument(String userMessage) {
        int start = userMessage.indexOf('{');
        try {
            return JSON.readTree(userMessage.substring(start));
        } catch (Exception failure) {
            throw new IllegalStateException(userMessage, failure);
        }
    }

    private ObjectNode normalizedGraph() {
        ObjectNode view = JSON.createObjectNode();
        Optional<GraphId> graphId = graph.currentGraphId();
        if (graphId.isEmpty()) {
            view.putArray("episodes");
            view.putArray("occurrences");
            view.putArray("edges");
            view.putNull("cursor");
            return view;
        }
        GraphId id = graphId.orElseThrow();
        ArrayNode episodes = view.putArray("episodes");
        ArrayNode occurrences = view.putArray("occurrences");
        for (SystemEpisode episode : graph.episodes(id)) {
            ObjectNode node = episodes.addObject();
            node.put("entrySource", episode.entrySource().name());
            node.put("systemAddress", episode.systemAddress());
            node.put("systemName", episode.systemName());
            node.put("active", episode.active());
            if (episode.completionReason() == null) {
                node.putNull("completionReason");
            } else {
                node.put(
                        "completionReason",
                        episode.completionReason().name()
                );
            }
            ArrayNode types = node.putArray("occurrenceTypes");
            for (EventOccurrence occurrence : episode.timeline()) {
                types.add(occurrence.eventType().value());
                ObjectNode recorded = occurrences.addObject();
                recorded.put("systemAddress", episode.systemAddress());
                recorded.put("episodeSequence", occurrence.episodeSequence());
                recorded.put("type", occurrence.eventType().value());
                recorded.put(
                        "source",
                        occurrence.source() == null
                                ? "RESTORED"
                                : occurrence.source().name()
                );
                recorded.put("sourceSequence", occurrence.sourceSequence());
                recorded.put(
                        "originalEventName",
                        occurrence.originalEventName()
                );
                // The body the record itself names, which is what a scanner
                // result is about. Deliberately not the body the graph had
                // established: during a system sweep that is the arrival star,
                // and every reading of the sweep would look like the same body.
                JsonNode reportedBody = occurrence.attributes().get("BodyID");
                if (reportedBody == null || !reportedBody.isIntegralNumber()) {
                    recorded.putNull("bodyId");
                } else {
                    recorded.put("bodyId", reportedBody.longValue());
                }
                // And the body the graph had established when it accepted the
                // occurrence, which answers a different question.
                if (occurrence.context().bodyId() == null) {
                    recorded.putNull("establishedBodyId");
                } else {
                    recorded.put(
                            "establishedBodyId",
                            occurrence.context().bodyId()
                    );
                }
            }
            ArrayNode transitions = node.putArray("transitions");
            for (OccurrenceTransition transition
                    : episode.occurrenceTransitions()) {
                transitions.add(transition.fromEventType().value()
                        + "->" + transition.toEventType().value());
            }
        }
        ArrayNode edges = view.putArray("edges");
        ShipBehaviorGraph shipGraph = graph.graph(id).orElseThrow();
        List<String> rendered = new ArrayList<>();
        for (TransitionEdge edge : shipGraph.edges()) {
            rendered.add(edge.key().fromEventType().value()
                    + "->" + edge.key().toEventType().value()
                    + "=" + edge.globalCounter().rawCount());
        }
        rendered.stream().sorted().forEach(edges::add);
        graph.cursor(id).ifPresentOrElse(
                cursor -> {
                    ObjectNode node = view.putObject("cursor");
                    node.put("eventType", cursor.eventType().value());
                },
                () -> view.putNull("cursor")
        );
        return view;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        bus.drainAndClose().toCompletableFuture().join();
        projection.shutdown().toCompletableFuture().join();
        observer.shutdown().toCompletableFuture().join();
        observerSubscription.close();
        captureSubscription.close();
        projectionSubscription.close();
        projectedBus.close();
        store.close();
        provider.shutdown();
    }

    // ------------------------------------------------------------ doubles

    /**
     * The provider, recorded rather than called.
     *
     * <p>It holds no HTTP client and no URL it could reach. {@code networkCalls}
     * exists so the report can state that the count is zero because nothing can
     * increment it, rather than because nobody looked.</p>
     */
    private static final class RecordingProvider implements LlmClient {

        private final List<ModelInput> inputs = new CopyOnWriteArrayList<>();
        private final AtomicInteger networkCalls = new AtomicInteger();
        private final AtomicInteger turn = new AtomicInteger();
        private final ProviderLatency latency;
        private final ScheduledExecutorService clock =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "full-replay-provider"
                    );
                    thread.setDaemon(true);
                    return thread;
                });

        private RecordingProvider(ProviderLatency latency) {
            this.latency = latency;
        }

        /**
         * Answers later, without holding the caller's thread.
         *
         * <p>A real provider is an HTTP call: the coordinator hands the request
         * over and goes back to processing commands, so triggers that arrive
         * during the call are queued with the arrival time they really had.
         * Sleeping inside this method instead would stall that queue and stamp
         * every waiting trigger late, which changes which triggers end up in
         * which batch — an artefact of the substitution rather than of
         * anything under test.</p>
         */
        @Override
        public CompletionStage<LlmResponse> complete(ModelInput input) {
            inputs.add(input);
            Duration pause = latency.forTurn(turn.incrementAndGet());
            LlmResponse response = new LlmResponse(SILENT, 1L);
            if (pause.isZero() || pause.isNegative()) {
                return CompletableFuture.completedFuture(response);
            }
            CompletableFuture<LlmResponse> answer = new CompletableFuture<>();
            clock.schedule(
                    () -> answer.complete(response),
                    pause.toNanos(),
                    TimeUnit.NANOSECONDS
            );
            return answer;
        }

        private void shutdown() {
            clock.shutdownNow();
        }

        @Override
        public ProviderDescriptor provider() {
            return new ProviderDescriptor(
                    "full-replay-regression",
                    "LM_STUDIO",
                    URI.create("http://127.0.0.1:0/never-called"),
                    "recording-stub"
            );
        }
    }

    /** A sink that counts and does nothing else: no console, no speech. */
    private static final class CountingSink implements CommentSink {

        private final AtomicInteger delivered = new AtomicInteger();
        private final AtomicInteger speechInvocations = new AtomicInteger();

        @Override
        public CompletionStage<CommentDeliveryResult> deliver(String comment) {
            delivered.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new CommentDeliveryResult(
                            speechDescriptor(),
                            ConsoleOutcome.NOT_ATTEMPTED,
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

    /** Which triggers the coordinator bound to which turn, in turn order. */
    private static final class RecordingListener
            implements ObserverTurnListener {

        private final Map<Long, List<Long>> byTurn = new HashMap<>();
        private final List<Long> order = new ArrayList<>();
        private final AtomicLong ignored = new AtomicLong();

        @Override
        public synchronized void onObservationEffectChanged(
                ObservationEffectChanged change
        ) {
            if (change.effect() != ObservationEffect.NEW_IN_FLIGHT
                    || change.turnSequence() == null) {
                ignored.incrementAndGet();
                return;
            }
            long turnSequence = change.turnSequence();
            if (!byTurn.containsKey(turnSequence)) {
                byTurn.put(turnSequence, new ArrayList<>());
                order.add(turnSequence);
            }
            byTurn.get(turnSequence).add(change.busSequence());
        }

        private synchronized Map<Long, List<Long>> triggersByTurn() {
            Map<Long, List<Long>> copy = new HashMap<>();
            byTurn.forEach((turn, triggers) ->
                    copy.put(turn, List.copyOf(triggers)));
            return Map.copyOf(copy);
        }

        private synchronized List<Long> turnOrder() {
            return List.copyOf(order);
        }
    }
}
