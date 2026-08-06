package kairon.observer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.bus.BehaviorGraphObservationProcessor;
import kairon.behavior.graph.BehaviorGraphQueryService;
import kairon.behavior.graph.BehaviorGraphService;
import kairon.behavior.persistence.InMemoryBehaviorGraphStore;
import kairon.config.KaironConfiguration.BehaviorGraphConfiguration;
import kairon.config.KaironConfiguration.ObserverConfiguration;
import kairon.llm.LlmClient;
import kairon.llm.LlmClient.LlmResponse;
import kairon.llm.LlmClient.ModelInput;
import kairon.llm.DecisionPromptFactory;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.bus.InProcessObservationBus;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser
        .CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser
        .ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.observation.journal.JournalObservationAdapter
        .JournalSourcePosition;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.source.ObservationSourceSignal
        .ObservationSourceSignalType;
import kairon.observer.decision.JacksonDecisionRequestSerializer;
import kairon.observer.decision.LlmDecisionRequestCompactor;
import kairon.observer.decision.LlmDecisionRequestFactory;
import kairon.observer.decision.DecisionTurnPolicy;
import kairon.output.ConsoleCommentSink;
import kairon.projection.ObservationProjectionCoordinator;
import kairon.projection.ObservationProjectionSubscriber;
import kairon.projection.ProjectedObservationBus;
import kairon.projection.ProjectedObservation;
import kairon.projection.RegistryBodyDetail;
import kairon.state.CurrentGameStateProjector;
import kairon.trace.JsonLinesTurnTraceWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SnapshotReplayIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void replayFlushUsesLastNewPostGraphSnapshot(
            @TempDir Path directory
    ) throws Exception {
        InProcessObservationBus rawBus = new InProcessObservationBus();
        ProjectedObservationBus projectedBus =
                new ProjectedObservationBus();
        InMemoryBehaviorGraphStore store =
                new InMemoryBehaviorGraphStore();
        BehaviorGraphService graph = new BehaviorGraphService(
                new BehaviorGraphConfiguration(
                        true,
                        directory.resolve("graphs"),
                        Duration.ofDays(30),
                        2.0,
                        50,
                        false
                ),
                store
        );
        BehaviorGraphQueryService query =
                new BehaviorGraphQueryService(graph);
        ObservationProjectionCoordinator projection =
                new ObservationProjectionCoordinator(
                        new CurrentGameStateProjector(),
                        Optional.of(
                                new BehaviorGraphObservationProcessor(graph)
                        ),
                        Optional.of(query),
                        projectedBus
                );
        ObservationProjectionSubscriber.Subscription projectionSubscription =
                new ObservationProjectionSubscriber(projection)
                        .subscribeTo(rawBus);
        RecordingLlmClient llm = new RecordingLlmClient();
        Path trace = directory.resolve("replay-turns.jsonl");
        ObserverTurnCoordinator observer =
                new ObserverTurnCoordinator(
                        new ObserverConfiguration(
                                "en",
                                60_000L,
                                120_000L,
                                trace
                        ),
                        new LlmDecisionRequestCompactor(
                new LlmDecisionRequestFactory(),
                new JacksonDecisionRequestSerializer(),
                DecisionTurnPolicy.production()),
                        new DecisionPromptFactory(),
                        llm,
                        new ConsoleCommentSink(new PrintStream(
                                new ByteArrayOutputStream(),
                                true,
                                StandardCharsets.UTF_8
                        )),
                        new JsonLinesTurnTraceWriter(trace)
                );
        LlmJournalObserverSubscriber.Subscriptions llmSubscription =
                new LlmJournalObserverSubscriber(observer)
                        .subscribeTo(projectedBus);
        JournalFixture journal = new JournalFixture();
        try {
            rawBus.publish(journal.journal(
                    ObservationCaptureMode.BOOTSTRAP,
                    """
                    {"timestamp":"2026-07-30T13:00:00Z",
                     "event":"LoadGame","FID":"F-REPLAY",
                     "ShipID":77,"Ship":"krait_mkii"}
                    """
            )).toCompletableFuture().join();
            rawBus.publish(journal.journal(
                    ObservationCaptureMode.REPLAY,
                    """
                    {"timestamp":"2026-07-30T13:00:01Z",
                     "event":"FSDJump","StarSystem":"Replay A",
                     "SystemAddress":7001}
                    """
            )).toCompletableFuture().join();
            rawBus.publish(journal.journal(
                    ObservationCaptureMode.REPLAY,
                    """
                    {"timestamp":"2026-07-30T13:00:02Z",
                     "event":"SupercruiseEntry",
                     "StarSystem":"Replay A","SystemAddress":7001}
                    """
            )).toCompletableFuture().join();
            rawBus.publish(journal.journal(
                    ObservationCaptureMode.REPLAY,
                    """
                    {"timestamp":"2026-07-30T13:00:03Z",
                     "event":"Friends","Status":"Online","Name":"Wingmate"}
                    """
            )).toCompletableFuture().join();
            rawBus.publish(journal.journal(
                    ObservationCaptureMode.REPLAY,
                    """
                    {"timestamp":"2026-07-30T13:00:04Z",
                     "event":"Location","StarSystem":"Replay A",
                     "SystemAddress":7001,"Docked":false}
                    """
            )).toCompletableFuture().join();
            rawBus.publish(journal.journal(
                    ObservationCaptureMode.REPLAY,
                    """
                    {"timestamp":"2026-07-30T13:00:05Z",
                     "event":"FSDJump","StarSystem":"Replay B",
                     "SystemAddress":7002}
                    """
            )).toCompletableFuture().join();
            rawBus.publish(journal.replayExhausted())
                    .toCompletableFuture().join();

            projection.awaitIdle().toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);
            observer.awaitIdle().toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);

            assertEquals(1, llm.inputs.size());
            JsonNode request = turn(llm.inputs.getFirst());
            assertEquals(
                    List.of(
                            "A ship jumped from one star system to another.",
                            "A ship entered supercruise from normal space.",
                            "Information about a friend's status was received.",
                            "A ship jumped from one star system to another."
                    ),
                    eventDescriptions(request)
            );
            JsonNode friends = request.path("events").get(2);
            assertFalse(
                    friends.has("relation"),
                    "an event's relation to the graph is a technical apply "
                            + "status the model never decided anything with"
            );
            // The turn rests on the last NEW trigger's snapshot: the system it
            // arrived in, never the one it left. The events still name both,
            // because both jumps genuinely happened.
            assertTrue(request.path("events").toString().contains("Replay B"));
            assertFalse(
                    request.path("context").toString().contains("Replay A"),
                    "the context is built from the final trigger's snapshot"
            );
            assertFalse(
                    request.path("context").has("system"),
                    "the final jump already names the system it arrived in"
            );
            assertFalse(
                    request.has("graphContext"),
                    "the behaviour graph advanced, and none of it was sent"
            );
            assertFalse(
                    request.toString().contains("SYSTEM_ENTRY"),
                    "no graph vocabulary reaches the model"
            );
            assertFalse(request.path("events").toString()
                    .contains("Location"));
            assertEquals(List.of(1, 2, 3, 4), eventPositions(request));
        } finally {
            rawBus.drainAndClose().toCompletableFuture().join();
            projection.shutdown().toCompletableFuture().join();
            observer.shutdown().toCompletableFuture().join();
            llmSubscription.close();
            projectionSubscription.close();
            store.close();
        }
    }

    @Test
    void planetDetailSurvivesScanAndBroadPlanetUpdateThroughPipeline(
            @TempDir Path directory
    ) throws Exception {
        InProcessObservationBus rawBus = new InProcessObservationBus();
        ProjectedObservationBus projectedBus =
                new ProjectedObservationBus();
        InMemoryBehaviorGraphStore store =
                new InMemoryBehaviorGraphStore();
        BehaviorGraphService graph = new BehaviorGraphService(
                new BehaviorGraphConfiguration(
                        true,
                        directory.resolve("graphs"),
                        Duration.ofDays(30),
                        2.0,
                        50,
                        false
                ),
                store
        );
        BehaviorGraphQueryService query =
                new BehaviorGraphQueryService(graph);
        ObservationProjectionCoordinator projection =
                new ObservationProjectionCoordinator(
                        new CurrentGameStateProjector(),
                        Optional.of(
                                new BehaviorGraphObservationProcessor(graph)
                        ),
                        Optional.of(query),
                        projectedBus
                );
        ObservationProjectionSubscriber.Subscription projectionSubscription =
                new ObservationProjectionSubscriber(projection)
                        .subscribeTo(rawBus);

        java.util.concurrent.atomic.AtomicReference<ProjectedObservation>
                finalProjection = new java.util.concurrent.atomic.AtomicReference<>();
        var projectionCapture = projectedBus.subscribe(
                "test-capture",
                finalProjection::set
        );

        RecordingLlmClient llm = new RecordingLlmClient();
        Path trace = directory.resolve("replay-turns.jsonl");
        ObserverTurnCoordinator observer =
                new ObserverTurnCoordinator(
                        new ObserverConfiguration(
                                "en",
                                60_000L,
                                120_000L,
                                trace
                        ),
                        new LlmDecisionRequestCompactor(
                new LlmDecisionRequestFactory(),
                new JacksonDecisionRequestSerializer(),
                DecisionTurnPolicy.production()),
                        new DecisionPromptFactory(),
                        llm,
                        new ConsoleCommentSink(new PrintStream(
                                new ByteArrayOutputStream(),
                                true,
                                StandardCharsets.UTF_8
                        )),
                        new JsonLinesTurnTraceWriter(trace)
                );
        LlmJournalObserverSubscriber.Subscriptions llmSubscription =
                new LlmJournalObserverSubscriber(observer)
                        .subscribeTo(projectedBus);

        JournalFixture journal = new JournalFixture();
        try {
            rawBus.publish(journal.journal(
                    ObservationCaptureMode.BOOTSTRAP,
                    """
                    {"timestamp":"2026-07-30T14:00:00Z",
                     "event":"LoadGame","FID":"F-REGRESSION",
                     "ShipID":51,"Ship":"krait_mkii",
                     "ShipName":"Regression"}
                    """
            )).toCompletableFuture().join();
            rawBus.publish(journal.journal(
                    ObservationCaptureMode.REPLAY,
                    """
                    {"timestamp":"2026-07-30T14:00:01Z",
                     "event":"FSDJump","StarSystem":"Regression",
                     "SystemAddress":7301}
                    """
            )).toCompletableFuture().join();
            rawBus.publish(journal.journal(
                    ObservationCaptureMode.REPLAY,
                    """
                    {"timestamp":"2026-07-30T14:00:02Z",
                     "event":"Scan","SystemAddress":7301,"BodyID":1,
                     "BodyName":"Test Body","PlanetClass":"Icy body"}
                    """
            )).toCompletableFuture().join();
            rawBus.publish(journal.journal(
                    ObservationCaptureMode.REPLAY,
                    """
                    {"timestamp":"2026-07-30T14:00:03Z",
                     "event":"ApproachBody","StarSystem":"Regression",
                     "SystemAddress":7301,"Body":"Test Body","BodyID":1}
                    """
            )).toCompletableFuture().join();
            rawBus.publish(journal.journal(
                    ObservationCaptureMode.REPLAY,
                    """
                    {"timestamp":"2026-07-30T14:00:04Z",
                     "event":"SupercruiseExit","StarSystem":"Regression",
                     "SystemAddress":7301,"Body":"Test Body","BodyID":1,
                     "BodyType":"Planet"}
                    """
            )).toCompletableFuture().join();
            rawBus.publish(journal.replayExhausted())
                    .toCompletableFuture().join();

            projection.awaitIdle().toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);
            observer.awaitIdle().toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);

            ProjectedObservation last = finalProjection.get();
            assertNotNull(last);
            assertEquals(
                    "PLANET",
                    new RegistryBodyDetail(last.systemRegistry())
                            .detailOf(7301L, 1L)
                            .broadBodyType()
            );
            assertEquals(
                    "Icy body",
                    new RegistryBodyDetail(last.systemRegistry())
                            .detailOf(7301L, 1L)
                            .planetClass()
            );
            assertEquals(
                    "Icy body",
                    graph.currentContext().bodyType(),
                    "graph body type should use same compatibility policy"
            );

            JsonNode request = turn(llm.inputs.getFirst());
            JsonNode body = request.path("context").path("body");
            assertEquals(
                    "Icy body",
                    body.path("planetClass").textValue(),
                    "a re-activated body keeps what is known about it"
            );
            assertFalse(
                    request.toString().contains("\"subject\":\"body\""),
                    "what is known about a body is standing background, "
                            + "never a change: " + request
            );
            assertFalse(
                    body.has("starType"),
                    "a planet has no star type, and an unestablished field "
                            + "is absent rather than null"
            );
            assertFalse(
                    request.toString().contains("bodyType"),
                    "the coarse type is redundant beside a planet class"
            );
        } finally {
            rawBus.drainAndClose().toCompletableFuture().join();
            projection.shutdown().toCompletableFuture().join();
            observer.shutdown().toCompletableFuture().join();
            projectionCapture.close();
            llmSubscription.close();
            projectionSubscription.close();
            store.close();
        }
    }

    private static JsonNode turn(ModelInput input) throws Exception {
        return JSON.readTree(input.userMessage());
    }

    private static JsonNode changeFor(JsonNode request, String subject) {
        for (JsonNode change : request.path("changes")) {
            if (subject.equals(change.path("subject").textValue())) {
                return change;
            }
        }
        return null;
    }

    /** What the model was actually told each event is. */
    private static List<String> eventDescriptions(JsonNode request) {
        return textValues(request.path("events"), "event");
    }

    /** What each event of the batch is, positionally, without any id. */
    private static List<Integer> eventPositions(JsonNode request) {
        List<Integer> result = new ArrayList<>();
        request.path("events").forEach(item -> {
            assertFalse(item.has("id"), "an event still carries an id");
            result.add(result.size() + 1);
        });
        return List.copyOf(result);
    }

    private static List<String> textValues(
            JsonNode array,
            String property
    ) {
        List<String> result = new ArrayList<>();
        array.forEach(item ->
                result.add(item.path(property).textValue()));
        return List.copyOf(result);
    }

    private static final class JournalFixture {

        private static final ObservationSource SOURCE =
                new ObservationSource("replay-test", "journal");

        private final JournalLineParser parser = new JournalLineParser();
        private final JournalObservationAdapter adapter =
                new JournalObservationAdapter(SOURCE);
        private long offset;

        private ObservationDraft<JournalEventObservation> journal(
                ObservationCaptureMode mode,
                String rawJson
        ) {
            byte[] bytes = rawJson.strip().getBytes(StandardCharsets.UTF_8);
            ParsedJournalRecord parsed =
                    (ParsedJournalRecord) parser.parse(
                            new CompleteJournalRecord(
                                    "Journal.replay-test.log",
                                    offset,
                                    bytes
                            )
                    );
            offset += bytes.length + 1L;
            return adapter.adapt(
                    parsed,
                    mode,
                    parsed.optionalJournalTimestamp().orElseThrow()
            );
        }

        private ObservationDraft<ObservationSourceSignal>
        replayExhausted() {
            return new ObservationDraft<>(
                    "replay-source-exhausted",
                    SOURCE,
                    new JournalSourcePosition(
                            "Journal.replay-test.log",
                            offset + 1
                    ),
                    Optional.empty(),
                    Instant.parse("2026-07-30T13:00:06Z"),
                    ObservationCaptureMode.REPLAY,
                    ObservationSourceSignal.SCHEMA_VERSION,
                    new ObservationSourceSignal(
                            ObservationSourceSignalType
                                    .REPLAY_SOURCE_EXHAUSTED
                    )
            );
        }
    }

    private static final class RecordingLlmClient implements LlmClient {

        private final List<ModelInput> inputs =
                new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<LlmResponse> complete(ModelInput input) {
            inputs.add(input);
            return CompletableFuture.completedFuture(new LlmResponse(
                    "{\"decision\":\"SILENT\"}",
                    1L
            ));
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
}
