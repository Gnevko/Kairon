package kairon.observer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.snapshot.BehaviorSituationCaptureStatus;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.config.KaironConfiguration.ObserverConfiguration;
import kairon.llm.LlmClient;
import kairon.llm.LlmClient.LlmResponse;
import kairon.llm.LlmClient.ModelInput;
import kairon.llm.DecisionPromptFactory;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.PublishedObservation;
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
import kairon.observer.decision.LlmDecisionRequest;
import kairon.observer.decision.LlmDecisionRequestCompactor;
import kairon.observer.decision.LlmDecisionRequestFactory;
import kairon.observer.decision.DecisionTurnPolicy;
import kairon.output.ConsoleCommentSink;
import kairon.projection.ProjectedObservation;
import kairon.projection.ProjectedObservationBus;
import kairon.projection.SemanticEnvelopeFactory;
import kairon.state.CurrentGameStateProjection;
import kairon.state.CurrentGameStateProjector;
import kairon.system.SystemRegistrySnapshot;
import kairon.trace.JsonLinesTurnTraceWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ObserverPipelineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void batchContainsOnlyNewTriggersAndUsesLastNewSnapshot(
            @TempDir Path directory
    ) throws Exception {
        RecordingLlmClient llm = RecordingLlmClient.silent();
        try (Harness harness = new Harness(
                directory.resolve("turns.jsonl"),
                llm,
                policy(8)
        )) {
            harness.publish("""
                    {"timestamp":"2026-07-30T10:00:00Z",
                     "event":"Undocked","StationName":"A"}
                    """);
            harness.publish("""
                    {"timestamp":"2026-07-30T10:00:01Z",
                     "event":"Location","StarSystem":"Context System",
                     "SystemAddress":8001,"Docked":false}
                    """);
            harness.publish("""
                    {"timestamp":"2026-07-30T10:00:02Z",
                     "event":"SupercruiseEntry",
                     "StarSystem":"Context System",
                     "SystemAddress":8001}
                    """);
            harness.finishReplay();

            assertEquals(1, llm.inputs.size());
            JsonNode request = turn(llm.inputs.getFirst());
            assertEquals(
                    List.of(
                            "A ship lifted off from a landing pad at a station, outpost or settlement.",
                            "A ship entered supercruise from normal space."
                    ),
                    eventDescriptions(request)
            );
            assertEquals(2, eventCount(request));
            assertFalse(
                    request.path("context").has("navigation"),
                    "the entry says it is in supercruise in its own words"
            );
            assertFalse(
                    request.path("context").has("system"),
                    "the supercruise event already names the system, so the "
                            + "context does not repeat it"
            );
            assertFalse(
                    request.has("graphContext"),
                    "the behaviour graph is internal knowledge, never input"
            );
            assertFalse(request.path("events").toString()
                    .contains("Location"));
            assertFalse(llm.inputs.getFirst().userMessage()
                    .contains("rawJson"));
        }
    }

    /**
     * A batch is one moment in the game, not one moment on the wall clock.
     *
     * <p>Both halves are asserted, because only the pair is a claim: the same
     * two events split when the journal puts minutes between them and stay
     * together when it puts a second between them. Nothing about their arrival
     * differs — in a replay both pairs arrive back to back, which is exactly how
     * a supercruise exit and a vehicle launch two and a half minutes apart came
     * to be read as one moment in the measured run.</p>
     */
    @Test
    void triggersFromDifferentMomentsAreNotOneBatch(
            @TempDir Path directory
    ) throws Exception {
        RecordingLlmClient apart = RecordingLlmClient.silent();
        try (Harness harness = new Harness(
                directory.resolve("apart.jsonl"),
                apart,
                policy(8)
        )) {
            harness.publish(supercruiseExit("2026-07-30T10:00:00Z"));
            harness.publish(launchFighter("2026-07-30T10:04:00Z"));
            harness.finishReplay();
        }

        assertEquals(
                2,
                apart.inputs.size(),
                "four minutes of game time is two moments"
        );
        assertEquals(
                List.of("A ship dropped out of supercruise into normal space."),
                eventDescriptions(turn(apart.inputs.getFirst()))
        );
        assertEquals(
                List.of("A vehicle was launched from the ship."),
                eventDescriptions(turn(apart.inputs.getLast()))
        );

        RecordingLlmClient together = RecordingLlmClient.silent();
        try (Harness harness = new Harness(
                directory.resolve("together.jsonl"),
                together,
                policy(8)
        )) {
            harness.publish(supercruiseExit("2026-07-30T10:00:00Z"));
            harness.publish(launchFighter("2026-07-30T10:00:01Z"));
            harness.finishReplay();
        }

        assertEquals(
                1,
                together.inputs.size(),
                "a second of game time is one moment"
        );
        assertEquals(2, eventCount(turn(together.inputs.getFirst())));
    }

    private static String supercruiseExit(String timestamp) {
        return """
                {"timestamp":"%s","event":"SupercruiseExit",
                 "StarSystem":"Context System","SystemAddress":8001,
                 "Body":"Context System 4 a","BodyID":20}
                """.formatted(timestamp);
    }

    private static String launchFighter(String timestamp) {
        return """
                {"timestamp":"%s","event":"LaunchFighter",
                 "Loadout":"base","ID":10,"PlayerControlled":true}
                """.formatted(timestamp);
    }

    @Test
    void contextAfterLastNewCannotReplaceItsCausalSnapshot(
            @TempDir Path directory
    ) throws Exception {
        RecordingLlmClient llm = RecordingLlmClient.silent();
        try (Harness harness = new Harness(
                directory.resolve("causal.jsonl"),
                llm,
                policy(8)
        )) {
            harness.publish("""
                    {"timestamp":"2026-07-30T10:10:00Z",
                     "event":"Undocked","StationName":"A"}
                    """);
            harness.publish("""
                    {"timestamp":"2026-07-30T10:10:01Z",
                     "event":"Location","StarSystem":"Later Context",
                     "SystemAddress":8002,"Docked":false}
                    """);
            harness.finishReplay();

            JsonNode request = turn(llm.inputs.getFirst());
            assertEquals(
                    List.of("A ship lifted off from a landing pad at a station, outpost or settlement."),
                    eventDescriptions(request));
            // The later Location established "Later Context". If it had
            // replaced this turn's snapshot the name would be present; an
            // unestablished field is absent, never a null placeholder.
            assertFalse(
                    request.path("context").path("system").has("name")
            );
            assertFalse(request.toString().contains("Later Context"));
            assertFalse(request.path("events").toString()
                    .contains("Location"));
        }
    }

    /**
     * The batching bound comes from the supplied policy, never from
     * {@code production()}.
     *
     * <p>The cutover briefly hardcoded the production policy in the
     * coordinator, which silently ignored configuration. A non-default bound
     * that is actually honoured is the only proof it is read.</p>
     */
    @Test
    void batchingUsesTheConfiguredPolicyRatherThanProductionDefaults(
            @TempDir Path directory
    ) throws Exception {
        RecordingLlmClient llm = RecordingLlmClient.silent();
        DecisionTurnPolicy configured = policy(2);
        assertNotEquals(
                DecisionTurnPolicy.production().maxTriggers(),
                configured.maxTriggers(),
                "the bound under test must differ from the default"
            );
        try (Harness harness = new Harness(
                directory.resolve("batching-policy.jsonl"),
                llm,
                configured
        )) {
            for (int index = 0; index < 4; index++) {
                harness.publish(scanOrganic(index));
            }
            harness.coordinator.awaitIdle().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            assertEquals(
                    2,
                    llm.inputs.size(),
                    "four triggers at a bound of two are two turns"
            );
            for (ModelInput input : llm.inputs) {
                assertEquals(
                        2,
                        eventCount(turn(input)),
                        "each turn carries exactly the configured bound"
                );
            }
        }
    }

    @Test
    void maximumTriggerLimitFlushesFifoWithoutDroppingNewEvents(
            @TempDir Path directory
    ) throws Exception {
        RecordingLlmClient llm = RecordingLlmClient.silent();
        Path trace = directory.resolve("bounded.jsonl");
        try (Harness harness = new Harness(trace, llm, policy(3))) {
            for (int index = 0; index < 4; index++) {
                harness.publish(scanOrganic(index));
            }
            harness.finishReplay();

            assertEquals(2, llm.inputs.size());
            assertEquals(3, eventCount(turn(llm.inputs.getFirst())));
            assertEquals(1, eventCount(turn(llm.inputs.getLast())));
            // Nothing was dropped: the trace still holds the internal
            // identities the local ids stood for, in arrival order.
            List<JsonNode> lines = traceLines(trace);
            assertEquals(
                    List.of(1L, 2L, 3L),
                    longValues(lines.getFirst().path("triggerBusSequences"))
            );
            assertEquals(
                    List.of(4L),
                    longValues(lines.getLast().path("triggerBusSequences"))
            );
        }
    }

    @Test
    void asynchronousModelCallKeepsOneTurnInFlight(
            @TempDir Path directory
    ) throws Exception {
        ManualLlmClient llm = new ManualLlmClient();
        Path trace = directory.resolve("async.jsonl");
        try (Harness harness = new Harness(trace, llm, policy(1))) {
            harness.publish(scanOrganic(1));
            ManualLlmClient.Pending first =
                    llm.awaitPending();
            harness.publish(scanOrganic(2));
            harness.coordinator.awaitApplied().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertEquals(1, llm.inputs.size());

            first.completeSilent();
            ManualLlmClient.Pending second =
                    llm.awaitPending();
            assertEquals(2, llm.inputs.size());
            second.completeSilent();
            harness.coordinator.awaitIdle().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertEquals(1, eventCount(turn(llm.inputs.getFirst())));
            assertEquals(1, eventCount(turn(llm.inputs.getLast())));
            assertEquals(
                    List.of(List.of(1L), List.of(2L)),
                    traceLines(trace).stream()
                            .map(line -> longValues(
                                    line.path("triggerBusSequences")
                            ))
                            .toList()
            );
        }
    }

    @Test
    void onlyThreeSuccessfullyDeliveredCommentsAreRemembered(
            @TempDir Path directory
    ) throws Exception {
        List<String> comments = List.of(
                "Hull integrity is stable.",
                "The new system is mapped.",
                "Organic sampling is complete.",
                "The wing contact acknowledged us."
        );
        AtomicInteger call = new AtomicInteger();
        RecordingLlmClient llm = new RecordingLlmClient(input -> {
            int index = call.getAndIncrement();
            if (index < comments.size()) {
                return comment(comments.get(index));
            }
            return silent();
        });
        try (Harness harness = new Harness(
                directory.resolve("comments.jsonl"),
                llm,
                policy(1)
        )) {
            harness.publish(scanOrganic(0));
            harness.finishReplay();
            for (int index = 1; index < 5; index++) {
                harness.publish(scanOrganic(index));
                harness.coordinator.awaitIdle().toCompletableFuture()
                        .get(2, TimeUnit.SECONDS);
            }

            JsonNode fifth = turn(llm.inputs.get(4));
            assertFalse(
                    fifth.has("previousComments"),
                    "generated comment text is never sent back to the model"
            );
            for (String delivered : comments) {
                assertFalse(
                        fifth.toString().contains(delivered),
                        "no earlier comment leaks into the context: "
                                + delivered
                );
            }
            assertEquals(
                    comments.subList(1, 4),
                    harness.coordinator.snapshot()
                            .toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)
                            .previousComments()
            );
        }
    }

    /**
     * A comment from a batch is attributed to the whole batch.
     *
     * <p>The model was shown three events and no identity for any of them, so
     * it could not have singled one out. The turn records every trigger it was
     * built from, and the trace still holds the internal mapping the request's
     * event order stands for — that mapping is now the only place the numbering
     * is written down.</p>
     */
    @Test
    void aCommentIsAttributedToEveryTriggerOfItsOwnTurn(
            @TempDir Path directory
    ) throws Exception {
        Path trace = directory.resolve("multi-trigger.jsonl");
        RecordingLlmClient llm = new RecordingLlmClient(input ->
                comment("The route observations support this comment.")
        );
        try (Harness harness = new Harness(trace, llm, policy(8))) {
            harness.publish(scanOrganic(1));
            harness.publish(scanOrganic(2));
            harness.publish(scanOrganic(3));
            harness.finishReplay();

            assertEquals(1, llm.inputs.size());
            assertEquals(3, eventCount(turn(llm.inputs.getFirst())));
            JsonNode traceLine = JSON.readTree(
                    Files.readAllLines(trace, StandardCharsets.UTF_8)
                            .getFirst()
            );
            assertEquals(
                    JsonLinesTurnTraceWriter.TRACE_SCHEMA_VERSION,
                    traceLine.path("traceSchemaVersion").textValue()
            );
            assertEquals(
                    "VALID",
                    traceLine.path("validatedDecision")
                            .path("status")
                            .textValue()
            );
            assertEquals(
                    List.of("comment", "decision", "failure", "status",
                            "violations"),
                    propertyNames(traceLine.path("validatedDecision")),
                    "the traced response describes the answer and no more"
            );
            assertEquals(
                    List.of(1L, 2L, 3L),
                    longValues(traceLine.path("triggerBusSequences")),
                    "the whole batch, recorded once, at the turn level"
            );
            assertEquals(
                    "DELIVERED",
                    traceLine.path("consoleOutcome").textValue()
            );
        }
    }

    /**
     * The removed citation is rejected, and the next turn is unaffected.
     *
     * <p>A model still answering the old contract sends a third property. That
     * is one invalid response — not a bad id, because there is no id to be bad
     * — and it neither delivers a comment nor spoils the turn after it.</p>
     */
    @Test
    void aRemovedCitationDoesNotLeakIntoTheFollowingTurn(
            @TempDir Path directory
    ) throws Exception {
        Path trace = directory.resolve("citation-recovery.jsonl");
        AtomicInteger call = new AtomicInteger();
        RecordingLlmClient llm = new RecordingLlmClient(input -> {
            if (call.getAndIncrement() == 0) {
                return commentCitingRemovedEvidence(
                        "This still answers the removed contract.",
                        1
                );
            }
            return comment("This answers the current contract.");
        });
        try (Harness harness = new Harness(trace, llm, policy(1))) {
            harness.publish(scanOrganic(1));
            harness.finishReplay();
            harness.publish(scanOrganic(2));
            harness.coordinator.awaitIdle().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            List<String> lines = Files.readAllLines(
                    trace,
                    StandardCharsets.UTF_8
            );
            assertEquals(2, lines.size());
            JsonNode invalid = JSON.readTree(lines.getFirst());
            JsonNode recovered = JSON.readTree(lines.getLast());
            assertEquals(
                    "INVALID",
                    invalid.path("validatedDecision")
                            .path("status")
                            .textValue()
            );
            assertEquals(
                    List.of("INVALID_PROPERTIES"),
                    textList(invalid.path("validatedDecision")
                            .path("violations")),
                    "a removed property is refused as a property, not as an id"
            );
            assertFalse(invalid.path("commentDelivered").booleanValue());
            assertEquals(
                    "VALID",
                    recovered.path("validatedDecision")
                            .path("status")
                            .textValue()
            );
            assertEquals(
                    List.of(3L),
                    longValues(recovered.path("triggerBusSequences"))
            );
        }
    }

    @Test
    void promptAndTraceContainTheSameSnapshotWithoutLegacyTimeline(
            @TempDir Path directory
    ) throws Exception {
        Path trace = directory.resolve("exact.jsonl");
        RecordingLlmClient llm = RecordingLlmClient.silent();
        try (Harness harness = new Harness(trace, llm, policy(8))) {
            harness.publish(scanOrganic(9));
            harness.finishReplay();

            ModelInput input = llm.inputs.getFirst();
            String prompt = input.systemMessage();
            assertTrue(prompt.contains("SILENT"));
            assertTrue(prompt.contains("COMMENT"));
            assertTrue(
                    prompt.contains(
                            "{\"decision\":\"COMMENT\",\"comment\":\"...\"}"
                    ),
                    "the comment response is a decision and a sentence"
            );
            assertTrue(
                    prompt.contains("You are Kairon"),
                    "the prompt says who she is, which is half of what is "
                            + "left of it"
            );
            for (String internal : List.of(
                    "busSequence",
                    "schema",
                    "graph",
                    "projection",
                    "sourceRole",
                    "diagnostic",
                    "currentState",
                    "stateChanges",
                    "prediction",
                    "previousComments",
                    "CURRENT SITUATION"
            )) {
                assertFalse(
                        prompt.contains(internal),
                        "the prompt must not name Kairon internals: "
                                + internal
                );
            }

            JsonNode traceLine = JSON.readTree(
                    Files.readAllLines(trace, StandardCharsets.UTF_8)
                            .getFirst()
            );
            String serializedTurn = input.userMessage();
            assertEquals(
                    LlmDecisionRequest.CONTEXT_SCHEMA,
                    traceLine.path("contextSchema").textValue()
            );
            assertEquals(
                    serializedTurn,
                    traceLine.path("situationTurn").textValue()
            );
            assertEquals(
                    input.userMessage(),
                    traceLine.path("modelInput")
                            .path("userMessage")
                            .textValue()
            );
            assertFalse(traceLine.has("eventBindings"));
            assertFalse(traceLine.toString().contains("sourceRawJson"));
            assertFalse(traceLine.toString().contains("\"alias\""));
        }
    }

    /**
     * A turn that would have contained only NPC chatter does not happen.
     *
     * <p>The observation is still published and still projected — its semantic
     * effect reaches the accumulator like any other — but it never enters the
     * trigger queue, so there is no batch, no request and no trace line.</p>
     */
    @Test
    void npcChatterAloneProducesNoProviderCall(@TempDir Path directory)
            throws Exception {
        Path trace = directory.resolve("npc-only.jsonl");
        RecordingLlmClient llm = RecordingLlmClient.silent();
        try (Harness harness = new Harness(trace, llm, policy(8))) {
            harness.publish(receiveText("npc", "Traffic control here."));
            harness.publish(receiveText("npc", "Docking bay clear."));
            harness.finishReplay();

            assertEquals(List.of(), llm.inputs, "the provider is not called");
            assertFalse(Files.exists(trace) && Files.size(trace) > 0);
            assertEquals(
                    3,
                    harness.coordinator.pendingSemanticEffectCount()
                            .toCompletableFuture().get(2, TimeUnit.SECONDS),
                    "both messages still contributed their semantic effect, "
                            + "alongside the replay-exhausted signal"
            );
        }
    }

    /**
     * A message addressed to the Commander still starts a turn.
     *
     * <p>Two channels are declined and neither is this one: {@code npc} is
     * ambient chatter, {@code squadron} is other Commanders talking to each
     * other. Someone writing to the Commander directly is what the rule leaves
     * alone, so the turn is the direct message's and carries nothing of the
     * other two.</p>
     */
    @Test
    void aMessageOnAnyOtherChannelStillStartsATurn(@TempDir Path directory)
            throws Exception {
        RecordingLlmClient llm = RecordingLlmClient.silent();
        try (Harness harness = new Harness(
                directory.resolve("direct.jsonl"),
                llm,
                policy(8)
        )) {
            harness.publish(receiveText("npc", "Traffic control here."));
            harness.publish(receiveText("squadron", "Nabend CMDRs o7"));
            harness.publish(receiveText("player", "Brauchst du Hilfe?"));
            harness.finishReplay();

            assertEquals(1, llm.inputs.size());
            JsonNode request = turn(llm.inputs.getFirst());
            assertEquals(
                    List.of("Another player sent a text message to a channel the Commander is in."),
                    eventDescriptions(request),
                    "only the message addressed to him is a trigger"
            );
            assertEquals(1, eventCount(request));
            assertEquals(
                    "PLAYER",
                    request.path("events").get(0).path("channel").textValue(),
                    "a closed vocabulary is sent in the contract's own casing"
            );
            assertFalse(request.toString().contains("Traffic control"));
            assertFalse(request.toString().contains("Nabend"));
        }
    }

    private static String receiveText(String channel, String message) {
        return """
                {"timestamp":"2026-07-30T11:30:00Z","event":"ReceiveText",
                 "Channel":"%s","From":"OLKI","Message":"%s"}
                """.formatted(channel, message);
    }

    private static String scanOrganic(int index) {
        return """
                {"timestamp":"2026-07-30T11:00:%02dZ",
                 "event":"ScanOrganic","ScanType":"Log",
                 "Genus_Localised":"Test genus %d",
                 "SystemAddress":9001,"Body":4}
                """.formatted(index % 60, index);
    }

    private static DecisionTurnPolicy policy(int maxTriggers) {
        return new DecisionTurnPolicy(
                maxTriggers,
                DecisionTurnPolicy.production().maxSerializedCharacters()
        );
    }

    private static JsonNode turn(ModelInput input) {
        try {
            return JSON.readTree(input.userMessage());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** What the model was actually told each event is. */
    private static List<String> eventDescriptions(JsonNode request) {
        return textValues(request.path("events"), "event");
    }

    /** How many events the batch actually carried. */
    private static int eventCount(JsonNode request) {
        return request.path("events").size();
    }

    private static List<JsonNode> traceLines(Path trace) throws Exception {
        List<JsonNode> lines = new ArrayList<>();
        for (String line
                : Files.readAllLines(trace, StandardCharsets.UTF_8)) {
            lines.add(JSON.readTree(line));
        }
        return List.copyOf(lines);
    }

    private static List<String> textList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.textValue()));
        return List.copyOf(values);
    }

    private static List<String> propertyNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }

    private static List<Long> longValues(JsonNode array) {
        List<Long> values = new ArrayList<>();
        array.forEach(value -> values.add(value.longValue()));
        return List.copyOf(values);
    }

    private static List<String> textValues(
            JsonNode array,
            String field
    ) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.path(field).textValue()));
        return List.copyOf(values);
    }

    private static String silent() {
        return "{\"decision\":\"SILENT\"}";
    }

    /** A decision and a sentence: the whole of the response contract. */
    private static String comment(String text) {
        return "{\"decision\":\"COMMENT\",\"comment\":\"" + text + "\"}";
    }

    /**
     * The response a model still running the removed contract would send.
     *
     * <p>Written out rather than assembled from a constant so the removal is
     * visible here: a third property, whatever it holds, is one property too
     * many.</p>
     */
    private static String commentCitingRemovedEvidence(String text, int id) {
        return "{\"decision\":\"COMMENT\",\"comment\":\""
                + text
                + "\",\"evidence\":[" + id + "]}";
    }

    private static final class Harness implements AutoCloseable {

        private final ProjectionFixture projection =
                new ProjectionFixture();
        private final ProjectedObservationBus projectedBus =
                new ProjectedObservationBus();
        private final ObserverTurnCoordinator coordinator;
        private final LlmJournalObserverSubscriber.Subscriptions
                subscription;

        private Harness(
                Path trace,
                LlmClient llm,
                DecisionTurnPolicy policy
        ) {
            coordinator = new ObserverTurnCoordinator(
                    new ObserverConfiguration(
                            "en",
                            60_000L,
                            120_000L,
                            trace
                    ),
                    new LlmDecisionRequestCompactor(
                new LlmDecisionRequestFactory(),
                new JacksonDecisionRequestSerializer(),
                policy),
                    new DecisionPromptFactory(),
                    llm,
                    new ConsoleCommentSink(new PrintStream(
                            new ByteArrayOutputStream(),
                            true,
                            StandardCharsets.UTF_8
                    )),
                    new JsonLinesTurnTraceWriter(trace)
            );
            subscription = new LlmJournalObserverSubscriber(coordinator)
                    .subscribeTo(projectedBus);
        }

        private void publish(String rawJson) {
            projectedBus.publish(projection.project(rawJson));
        }

        private void finishReplay() throws Exception {
            projectedBus.publish(projection.replayExhausted());
            coordinator.awaitIdle().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            subscription.close();
            projectedBus.close();
            coordinator.close();
        }
    }

    private static final class ProjectionFixture {

        private static final ObservationSource SOURCE =
                new ObservationSource("observer-test", "journal");

        private final JournalLineParser parser = new JournalLineParser();
        private final JournalObservationAdapter adapter =
                new JournalObservationAdapter(SOURCE);
        private final CurrentGameStateProjector state =
                new CurrentGameStateProjector();
        private long busSequence;
        private long sourceOffset;

        private ProjectedObservation project(String rawJson) {
            byte[] bytes = rawJson.strip().getBytes(StandardCharsets.UTF_8);
            ParsedJournalRecord parsed =
                    (ParsedJournalRecord) parser.parse(
                            new CompleteJournalRecord(
                                    "Journal.observer-test.log",
                                    sourceOffset,
                                    bytes
                            )
                    );
            sourceOffset += bytes.length + 1L;
            ObservationDraft<JournalEventObservation> draft =
                    adapter.adapt(
                            parsed,
                            ObservationCaptureMode.REPLAY,
                            parsed.optionalJournalTimestamp()
                                    .orElse(Instant.EPOCH)
                    );
            PublishedObservation<JournalEventObservation> published =
                    new PublishedObservation<>(
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
            return disabledProjection(published);
        }

        private ProjectedObservation replayExhausted() {
            PublishedObservation<ObservationSourceSignal> published =
                    new PublishedObservation<>(
                            "observer-replay-exhausted-" + (busSequence + 1),
                            ++busSequence,
                            SOURCE,
                            new JournalSourcePosition(
                                    "Journal.observer-test.log",
                                    sourceOffset + 1
                            ),
                            Optional.empty(),
                            Instant.parse("2026-07-30T12:00:00Z"),
                            ObservationCaptureMode.REPLAY,
                            ObservationSourceSignal.SCHEMA_VERSION,
                            new ObservationSourceSignal(
                                    ObservationSourceSignalType
                                            .REPLAY_SOURCE_EXHAUSTED
                            )
                    );
            return disabledProjection(published);
        }

        private ProjectedObservation disabledProjection(
                PublishedObservation<?> published
        ) {
            CurrentGameStateProjection projectedState =
                    state.applyAndCapture(published);
            BehaviorGraphApplyResult graph =
                    BehaviorGraphApplyResult.disabled(
                            published.busSequence()
                    );
            return new ProjectedObservation(
                    published,
                    projectedState.applied(),
                    projectedState.changes(),
                    graph,
                    BehaviorSituationSnapshot.unavailable(
                            graph,
                            BehaviorSituationCaptureStatus.GRAPH_DISABLED
                    ),
                    SemanticEnvelopeFactory.production().create(
                            published,
                            projectedState.applied()
                    ),
                    SystemRegistrySnapshot.empty(published.busSequence())
            );
        }
    }

    private static class RecordingLlmClient implements LlmClient {

        private final List<ModelInput> inputs =
                new CopyOnWriteArrayList<>();
        private final Function<ModelInput, String> response;

        private RecordingLlmClient(Function<ModelInput, String> response) {
            this.response = response;
        }

        private static RecordingLlmClient silent() {
            return new RecordingLlmClient(ignored -> ObserverPipelineTest
                    .silent());
        }

        @Override
        public CompletionStage<LlmResponse> complete(ModelInput input) {
            inputs.add(input);
            return CompletableFuture.completedFuture(
                    new LlmResponse(response.apply(input), 1L)
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

    private static final class ManualLlmClient implements LlmClient {

        private final List<ModelInput> inputs =
                new CopyOnWriteArrayList<>();
        private final BlockingQueue<Pending> pending =
                new LinkedBlockingQueue<>();

        @Override
        public CompletionStage<LlmResponse> complete(ModelInput input) {
            inputs.add(input);
            Pending call = new Pending();
            pending.add(call);
            return call.response;
        }

        private Pending awaitPending() throws InterruptedException {
            Pending value = pending.poll(2, TimeUnit.SECONDS);
            if (value == null) {
                throw new AssertionError("model call was not started");
            }
            return value;
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

        private static final class Pending {

            private final CompletableFuture<LlmResponse> response =
                    new CompletableFuture<>();

            private void completeSilent() {
                response.complete(new LlmResponse(silent(), 1L));
            }
        }
    }
}
