package kairon.observer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.snapshot.BehaviorSituationCaptureStatus;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.config.KaironConfiguration;
import kairon.config.KaironConfiguration.ObserverConfiguration;
import kairon.config.KaironConfiguration.SpeechAudioEncoding;
import kairon.config.KaironConfiguration.SpeechConfiguration;
import kairon.config.KaironConfiguration.SpeechProvider;
import kairon.llm.LlmClient;
import kairon.llm.DecisionPromptFactory;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.PublishedObservation;
import kairon.observation.bus.InProcessObservationBus;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.JournalObservationAdapter.JournalSourcePosition;
import kairon.observation.journal.event.exploration.ScanOrganic;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.source.ObservationSourceSignal.ObservationSourceSignalType;
import kairon.observer.decision.JacksonDecisionRequestSerializer;
import kairon.observer.decision.LlmDecisionRequestCompactor;
import kairon.observer.decision.LlmDecisionRequestFactory;
import kairon.observer.decision.DecisionTurnPolicy;
import kairon.output.CommentSink;
import kairon.output.CommentSink.CommentDeliveryResult;
import kairon.output.CommentSink.ConsoleOutcome;
import kairon.output.CommentSink.SpeechDescriptor;
import kairon.output.CommentSink.SpeechOutcome;
import kairon.output.ConsoleCommentSink;
import kairon.output.SpeechGateway;
import kairon.output.SpeechGateway.SpeechHandle;
import kairon.output.SpeechGateway.SpeechRequest;
import kairon.projection.ProjectedObservation;
import kairon.projection.SemanticEnvelopeFactory;
import kairon.speech.AudioPlayer;
import kairon.speech.AudioPlayer.AudioPlaybackException;
import kairon.speech.SpeechSynthesisClient;
import kairon.speech.SpeechSynthesisClient.SpeechFailureCategory;
import kairon.speech.SpeechSynthesisClient.SpeechSynthesisException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SpeechOutputTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObservationSource SOURCE =
            new ObservationSource("elite-dangerous-journal", "speech-test");
    private static final byte[] WAV_BYTES =
            "RIFF-test-WAVE-payload".getBytes(StandardCharsets.US_ASCII);

    @Test
    void silentCausesNoSynthesisOrPlayback(@TempDir Path directory)
            throws Exception {
        RecordingSynthesis synthesis = new RecordingSynthesis(WAV_BYTES);
        RecordingAudioPlayer player = new RecordingAudioPlayer();
        SpeechGateway sink = speechGateway(synthesis, player, true);
        Path trace = directory.resolve("silent.jsonl");
        ObserverTurnCoordinator coordinator = coordinator(
                trace,
                new FixedLlmClient(
                        "{\"decision\":\"SILENT\"}"
                ),
                sink
        );
        try {
            completeReplayTurn(coordinator, 1);

            assertEquals(0, synthesis.callCount());
            assertEquals(0, player.callCount());
            JsonNode turn = firstTrace(trace);
            assertEquals("NOT_ATTEMPTED",
                    turn.path("consoleOutcome").textValue());
            assertEquals("NOT_REQUESTED",
                    turn.path("speechOutcome").textValue());
            assertNull(turn.path("deliveredComment").textValue());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void commentTextReachesSynthesisUnchanged() {
        RecordingSynthesis synthesis = new RecordingSynthesis(WAV_BYTES);
        RecordingAudioPlayer player = new RecordingAudioPlayer();
        try (SpeechGateway sink = speechGateway(synthesis, player, false)) {
            String comment = "Командир, сигнал принят — без переписывания.";

            CommentDeliveryResult result =
                    sink.deliver(comment).toCompletableFuture().join();

            assertEquals(List.of(comment), synthesis.texts());
            assertEquals(SpeechOutcome.DELIVERED,
                    result.speechResult().outcome());
        }
    }

    @Test
    void linear16WavBytesReachAudioPlayerUnchanged() {
        byte[] source = Arrays.copyOf(WAV_BYTES, WAV_BYTES.length);
        RecordingSynthesis synthesis = new RecordingSynthesis(source);
        RecordingAudioPlayer player = new RecordingAudioPlayer();
        try (SpeechGateway sink = speechGateway(synthesis, player, false)) {
            sink.deliver("Exact audio.").toCompletableFuture().join();

            assertEquals(1, player.audio().size());
            assertArrayEquals(source, player.audio().getFirst());
            assertArrayEquals(WAV_BYTES, source);
        }
    }

    @Test
    void synthesisWithoutPlaybackCompletionIsNotDelivered()
            throws Exception {
        RecordingSynthesis synthesis = new RecordingSynthesis(WAV_BYTES);
        RecordingAudioPlayer player = RecordingAudioPlayer.blocking();
        try (SpeechGateway sink = speechGateway(synthesis, player, false)) {
            CompletableFuture<CommentDeliveryResult> delivery =
                    sink.deliver("Wait for playback.")
                            .toCompletableFuture();
            assertTrue(player.entered.await(2, TimeUnit.SECONDS));

            assertFalse(delivery.isDone());
            assertEquals(SpeechOutcome.PLAYING, sink.currentState());

            player.release.countDown();
            assertEquals(
                    SpeechOutcome.DELIVERED,
                    delivery.get(2, TimeUnit.SECONDS)
                            .speechResult().outcome()
            );
        }
    }

    @Test
    void completedPlaybackMarksSpeechDelivered() {
        RecordingSynthesis synthesis = new RecordingSynthesis(WAV_BYTES);
        RecordingAudioPlayer player = new RecordingAudioPlayer();
        try (SpeechGateway sink = speechGateway(synthesis, player, true)) {
            CommentDeliveryResult result =
                    sink.deliver("Playback completed.")
                            .toCompletableFuture().join();

            assertTrue(result.deliveredForHistory());
            assertEquals(ConsoleOutcome.DELIVERED, result.consoleOutcome());
            assertEquals(SpeechOutcome.DELIVERED,
                    result.speechResult().outcome());
            assertNotNull(result.speechResult().synthesisStartedAt());
            assertNotNull(result.speechResult().synthesisCompletedAt());
            assertNotNull(result.speechResult().playbackStartedAt());
            assertNotNull(result.speechResult().playbackCompletedAt());
        }
    }

    @Test
    void synthesisFailureProducesNoPlayback() {
        RecordingSynthesis synthesis = new RecordingSynthesis(WAV_BYTES);
        synthesis.checkedFailure = new SpeechSynthesisException(
                SpeechFailureCategory.SYNTHESIS_REQUEST
        );
        RecordingAudioPlayer player = new RecordingAudioPlayer();
        try (SpeechGateway sink = speechGateway(synthesis, player, false)) {
            CommentDeliveryResult result =
                    sink.deliver("Synthesis fails.")
                            .toCompletableFuture().join();

            assertEquals(SpeechOutcome.SYNTHESIS_FAILED,
                    result.speechResult().outcome());
            assertEquals(
                    SpeechFailureCategory.SYNTHESIS_REQUEST,
                    result.speechResult().failureCategory()
            );
            assertEquals(0, player.callCount());
            assertFalse(result.deliveredForHistory());
        }
    }

    @Test
    void playbackFailureDoesNotUpdatePreviousCommentHistory(
            @TempDir Path directory
    ) throws Exception {
        RecordingSynthesis synthesis = new RecordingSynthesis(WAV_BYTES);
        RecordingAudioPlayer player = new RecordingAudioPlayer();
        player.checkedFailure = new AudioPlaybackException(
                SpeechFailureCategory.AUDIO_LINE
        );
        SpeechGateway sink = speechGateway(synthesis, player, true);
        Path trace = directory.resolve("playback-failure.jsonl");
        ObserverTurnCoordinator coordinator = coordinator(
                trace,
                new FixedLlmClient(commentResponse("Not heard.")),
                sink
        );
        try {
            completeReplayTurn(coordinator, 2);

            assertTrue(coordinator.snapshot().toCompletableFuture().join()
                    .previousComments().isEmpty());
            JsonNode turn = firstTrace(trace);
            assertEquals("DELIVERED",
                    turn.path("consoleOutcome").textValue());
            assertEquals("PLAYBACK_FAILED",
                    turn.path("speechOutcome").textValue());
            assertEquals("AUDIO_LINE",
                    turn.path("speechFailureCategory").textValue());
            assertNull(turn.path("deliveredComment").textValue());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void speechGatewaySerializesAndCancelsOnlyTheTargetRequest()
            throws Exception {
        RecordingSynthesis synthesis = new RecordingSynthesis(WAV_BYTES);
        RecordingAudioPlayer player = new RecordingAudioPlayer();
        player.playDelayMs = 40L;
        try (SpeechGateway sink = speechGateway(synthesis, player, false)) {
            CompletableFuture<CommentDeliveryResult> first =
                    sink.deliver("First.").toCompletableFuture();
            CompletableFuture<CommentDeliveryResult> second =
                    sink.deliver("Second.").toCompletableFuture();

            CompletableFuture.allOf(first, second).join();
            assertEquals(2, player.callCount());
            assertEquals(1, player.maximumConcurrent.get());
        }

        RecordingSynthesis queuedSynthesis =
                RecordingSynthesis.blocking(WAV_BYTES);
        RecordingAudioPlayer queuedPlayer = new RecordingAudioPlayer();
        try (SpeechGateway gateway = speechGateway(
                queuedSynthesis,
                queuedPlayer,
                false
        )) {
            SpeechHandle active = gateway.submit(
                    new SpeechRequest("active-request", "First.")
            );
            assertTrue(queuedSynthesis.entered.await(
                    2,
                    TimeUnit.SECONDS
            ));
            SpeechHandle queued = gateway.submit(
                    new SpeechRequest("queued-request", "Never spoken.")
            );

            assertThrows(
                    IllegalArgumentException.class,
                    () -> gateway.submit(new SpeechRequest(
                            "active-request",
                            "Duplicate."
                    ))
            );
            assertFalse(gateway.cancel("unknown-request"));
            assertTrue(gateway.cancel("queued-request"));
            assertEquals(
                    SpeechOutcome.CANCELLED,
                    queued.completion().toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)
                            .speechResult().outcome()
            );
            assertFalse(active.completion().toCompletableFuture().isDone());

            queuedSynthesis.release.countDown();
            assertEquals(
                    SpeechOutcome.DELIVERED,
                    active.completion().toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)
                            .speechResult().outcome()
            );
            assertEquals(List.of("First."), queuedSynthesis.texts());
            assertEquals(0, queuedSynthesis.cancellationCount.get());
        }

        RecordingSynthesis activeSynthesis =
                RecordingSynthesis.blocking(WAV_BYTES);
        RecordingAudioPlayer synthesisPlayer = new RecordingAudioPlayer();
        try (SpeechGateway gateway = speechGateway(
                activeSynthesis,
                synthesisPlayer,
                false
        )) {
            SpeechHandle cancelled = gateway.submit(
                    new SpeechRequest("synthesis-request", "Cancel synthesis.")
            );
            assertTrue(activeSynthesis.entered.await(
                    2,
                    TimeUnit.SECONDS
            ));

            assertTrue(cancelled.cancel());
            assertEquals(
                    SpeechOutcome.CANCELLED,
                    cancelled.completion().toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)
                            .speechResult().outcome()
            );
            assertTrue(activeSynthesis.finished.await(
                    2,
                    TimeUnit.SECONDS
            ));
            assertEquals(1, activeSynthesis.cancellationCount.get());

            assertEquals(
                    SpeechOutcome.DELIVERED,
                    gateway.submit(new SpeechRequest(
                                    "after-synthesis-cancel",
                                    "Still reusable."
                            ))
                            .completion().toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)
                            .speechResult().outcome()
            );
        }

        RecordingSynthesis playbackSynthesis =
                new RecordingSynthesis(WAV_BYTES);
        RecordingAudioPlayer activePlayer =
                RecordingAudioPlayer.blocking();
        try (SpeechGateway gateway = speechGateway(
                playbackSynthesis,
                activePlayer,
                false
        )) {
            SpeechHandle cancelled = gateway.submit(
                    new SpeechRequest("playback-request", "Cancel playback.")
            );
            assertTrue(activePlayer.entered.await(2, TimeUnit.SECONDS));

            assertTrue(cancelled.cancel());
            assertEquals(
                    SpeechOutcome.CANCELLED,
                    cancelled.completion().toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)
                            .speechResult().outcome()
            );
            assertEquals(1, activePlayer.cancellationCount.get());

            assertEquals(
                    SpeechOutcome.DELIVERED,
                    gateway.submit(new SpeechRequest(
                                    "after-playback-cancel",
                                    "Playback remains reusable."
                            ))
                            .completion().toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)
                            .speechResult().outcome()
            );
            assertFalse(cancelled.cancel());
        }
    }

    @Test
    void speechWorkNeverRunsOnObservationBusThreadAndDoesNotBlockIt()
            throws Exception {
        RecordingSynthesis synthesis = new RecordingSynthesis(WAV_BYTES);
        RecordingAudioPlayer player = RecordingAudioPlayer.blocking();
        SpeechGateway sink = speechGateway(synthesis, player, false);
        ObserverTurnCoordinator coordinator = coordinator(
                Files.createTempFile("speech-bus-", ".jsonl"),
                new FixedLlmClient(commentResponse("Bus stays free.")),
                sink
        );
        InProcessObservationBus bus = new InProcessObservationBus();
        ProjectedObservationTestBridge projectionBridge =
                new ProjectedObservationTestBridge(bus, coordinator);
        LlmJournalObserverSubscriber.Subscriptions subscriptions =
                projectionBridge.llmSubscriptions();
        try {
            bus.publish(draft(40L, ObservationCaptureMode.REPLAY))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            bus.publish(replayExhaustedDraft())
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(player.entered.await(2, TimeUnit.SECONDS));

            long startedAt = System.nanoTime();
            var laterReceipt =
                    bus.publish(draft(41L, ObservationCaptureMode.REPLAY))
                            .toCompletableFuture().get(2, TimeUnit.SECONDS);
            long handoffMillis = Duration.ofNanos(
                    System.nanoTime() - startedAt
            ).toMillis();

            assertTrue(laterReceipt.busSequence() > 0L);
            assertFalse(laterReceipt.matchedSubscriberIds().isEmpty());
            assertTrue(handoffMillis < 1_000L);
            assertEquals(List.of("speech-output"),
                    synthesis.threadNames());
            assertEquals(List.of("speech-output"),
                    player.threadNames());

            player.release.countDown();
            coordinator.awaitIdle().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertTrue(synthesis.threadNames().stream()
                    .allMatch("speech-output"::equals));
            assertTrue(player.threadNames().stream()
                    .allMatch("speech-output"::equals));
        } finally {
            player.release.countDown();
            coordinator.close();
            projectionBridge.close();
            bus.drainAndClose().toCompletableFuture().join();
        }
    }

    @Test
    void disablingSpeechRetainsConsoleDelivery() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SpeechDescriptor descriptor = SpeechDescriptor.disabled(
                "GOOGLE_CLOUD_TTS",
                "configured-but-disabled"
        );
        try (ConsoleCommentSink sink = new ConsoleCommentSink(
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                descriptor
        )) {
            CommentDeliveryResult result =
                    sink.deliver("Console only.")
                            .toCompletableFuture().join();

            assertEquals(
                    "Console only." + System.lineSeparator(),
                    bytes.toString(StandardCharsets.UTF_8)
            );
            assertEquals(ConsoleOutcome.DELIVERED, result.consoleOutcome());
            assertEquals(SpeechOutcome.DISABLED,
                    result.speechResult().outcome());
            assertTrue(result.deliveredForHistory());
        }
    }

    @Test
    void liveAndReplayUseTheSameSpeechOutputPath(@TempDir Path directory)
            throws Exception {
        List<String> spoken = new ArrayList<>();
        for (ObservationCaptureMode mode : List.of(
                ObservationCaptureMode.LIVE,
                ObservationCaptureMode.REPLAY
        )) {
            RecordingSynthesis synthesis = new RecordingSynthesis(WAV_BYTES);
            RecordingAudioPlayer player = new RecordingAudioPlayer();
            SpeechGateway sink = speechGateway(synthesis, player, false);
            ObserverTurnCoordinator coordinator = coordinator(
                    directory.resolve(mode.name() + ".jsonl"),
                    new FixedLlmClient(commentResponse(mode.name())),
                    sink,
                    10L,
                    50L
            );
            try {
                coordinator.post(new ObserverCommand.QueueNewObservation(
                        projected(observation(
                                mode.ordinal() + 20L,
                                mode
                        ))
                ));
                if (mode == ObservationCaptureMode.REPLAY) {
                    coordinator.post(new ObserverCommand.ReplaySourceExhausted(
                            replayExhaustedSignal()
                    ));
                }
                coordinator.awaitIdle().toCompletableFuture()
                        .get(2, TimeUnit.SECONDS);
                spoken.addAll(synthesis.texts());
                assertEquals(
                        List.of(mode.name()),
                        coordinator.snapshot().toCompletableFuture().join()
                                .previousComments()
                );
            } finally {
                coordinator.close();
            }
        }

        assertEquals(List.of("LIVE", "REPLAY"), spoken);
    }

    @Test
    void credentialsAndTokensAreAbsentFromLogsAndTurnTrace(
            @TempDir Path directory
    ) throws Exception {
        String secret = "google-token-" + UUID.randomUUID();
        RecordingSynthesis synthesis = new RecordingSynthesis(WAV_BYTES);
        synthesis.runtimeFailure = new IllegalStateException(secret);
        RecordingAudioPlayer player = new RecordingAudioPlayer();
        SpeechGateway sink = speechGateway(synthesis, player, false);
        Path trace = directory.resolve("safe-failure.jsonl");
        ObserverTurnCoordinator coordinator = coordinator(
                trace,
                new FixedLlmClient(commentResponse("Safe failure.")),
                sink
        );
        PrintStream originalError = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
        try {
            completeReplayTurn(coordinator, 30);
        } finally {
            System.setErr(originalError);
            coordinator.close();
        }

        String traceText = Files.readString(trace, StandardCharsets.UTF_8);
        String errorText = errors.toString(StandardCharsets.UTF_8);
        assertFalse(traceText.contains(secret));
        assertFalse(errorText.contains(secret));
        assertFalse(traceText.toLowerCase().contains("authorization"));
        assertFalse(traceText.toLowerCase().contains("credential"));
        assertEquals("INTERNAL",
                firstTrace(trace).path("speechFailureCategory").textValue());
    }

    @Test
    void shutdownCancelsAndClosesActiveSpeechResourcesDeterministically()
            throws Exception {
        RecordingSynthesis synthesis = RecordingSynthesis.blocking(WAV_BYTES);
        RecordingAudioPlayer player = new RecordingAudioPlayer();
        player.throwOnClose = true;
        SpeechGateway sink = speechGateway(synthesis, player, false);
        CompletableFuture<CommentDeliveryResult> delivery =
                sink.deliver("Cancel during synthesis.")
                        .toCompletableFuture();
        assertTrue(synthesis.entered.await(2, TimeUnit.SECONDS));

        sink.close();

        assertEquals(
                SpeechOutcome.CANCELLED,
                delivery.get(2, TimeUnit.SECONDS)
                        .speechResult().outcome()
        );
        assertTrue(synthesis.closed.get());
        assertTrue(player.closed.get());

        RecordingSynthesis playbackSynthesis =
                new RecordingSynthesis(WAV_BYTES);
        RecordingAudioPlayer blockingPlayer =
                RecordingAudioPlayer.blocking();
        SpeechGateway playbackSink = speechGateway(
                playbackSynthesis,
                blockingPlayer,
                false
        );
        CompletableFuture<CommentDeliveryResult> playbackDelivery =
                playbackSink.deliver("Cancel during playback.")
                        .toCompletableFuture();
        assertTrue(blockingPlayer.entered.await(2, TimeUnit.SECONDS));

        playbackSink.close();

        assertEquals(
                SpeechOutcome.CANCELLED,
                playbackDelivery.get(2, TimeUnit.SECONDS)
                        .speechResult().outcome()
        );
        assertTrue(blockingPlayer.closed.get());

        RecordingSynthesis nonCooperative =
                RecordingSynthesis.blocking(WAV_BYTES);
        nonCooperative.ignoreCancellation = true;
        RecordingAudioPlayer unusedPlayer = new RecordingAudioPlayer();
        SpeechGateway boundedSink = speechGateway(
                nonCooperative,
                unusedPlayer,
                false
        );
        CompletableFuture<CommentDeliveryResult> active =
                boundedSink.deliver("Non-cooperative active.")
                        .toCompletableFuture();
        CompletableFuture<CommentDeliveryResult> queued =
                boundedSink.deliver("Queued.")
                        .toCompletableFuture();
        assertTrue(nonCooperative.entered.await(2, TimeUnit.SECONDS));
        long startedAt = System.nanoTime();

        boundedSink.close();

        long closeMillis = Duration.ofNanos(
                System.nanoTime() - startedAt
        ).toMillis();
        assertTrue(closeMillis < 2_000L);
        assertEquals(SpeechOutcome.CANCELLED,
                active.join().speechResult().outcome());
        assertEquals(SpeechOutcome.CANCELLED,
                queued.join().speechResult().outcome());
        assertEquals(SpeechOutcome.CANCELLED, boundedSink.currentState());

        nonCooperative.release.countDown();
        assertTrue(nonCooperative.finished.await(2, TimeUnit.SECONDS));
        assertEquals(
                SpeechOutcome.CANCELLED,
                active.join().speechResult().outcome()
        );
    }

    @Test
    void invalidSpeechConfigurationFailsBeforeObservationStarts(
            @TempDir Path directory
    ) throws Exception {
        String googleSecret = "google-test-" + UUID.randomUUID();
        Files.writeString(
                directory.resolve("authentication.json"),
                """
                        {
                          "llm": {"providers": {}},
                          "speech": {
                            "googleCloudTts": {
                              "apiKey": "%s"
                            }
                          }
                        }
                        """.formatted(googleSecret),
                StandardCharsets.UTF_8
        );
        Path replay = Files.writeString(
                directory.resolve("Journal.test.log"),
                "{\"event\":\"FSDJump\"}\n",
                StandardCharsets.UTF_8
        );
        ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(
                Path.of("config", "kairon.example.json"),
                StandardCharsets.UTF_8
        ));
        ObjectNode source = (ObjectNode) root.path("source");
        source.put("mode", "replay");
        source.putNull("journalDirectory");
        source.put("replayFile", replay.toString());

        ObjectNode disabledRoot = root.deepCopy();
        ObjectNode disabledSpeech =
                (ObjectNode) disabledRoot.path("speech");
        disabledSpeech.removeAll();
        disabledSpeech.put("enabled", false);
        Path disabled = directory.resolve("disabled-defaults.json");
        JSON.writeValue(disabled.toFile(), disabledRoot);
        SpeechConfiguration defaults =
                KaironConfiguration.load(disabled).speech();
        assertFalse(defaults.enabled());
        assertEquals(SpeechProvider.GOOGLE_CLOUD_TTS, defaults.provider());
        assertEquals(SpeechAudioEncoding.LINEAR16, defaults.audioEncoding());
        assertEquals(Duration.ofSeconds(15), defaults.requestTimeout());

        ObjectNode missingEnabled = root.deepCopy();
        ((ObjectNode) missingEnabled.path("speech")).remove("enabled");
        assertConfigurationFailure(
                directory,
                "missing-enabled.json",
                missingEnabled,
                "CONFIG_REQUIRED_VALUE_MISSING"
        );

        ObjectNode speech = (ObjectNode) root.path("speech");
        speech.put("enabled", true);

        assertConfigurationFailure(
                directory,
                "placeholder.json",
                root,
                "CONFIG_SPEECH_VOICE_PLACEHOLDER"
        );

        speech.put("voiceName", "ru-RU-Standard-A");
        speech.remove("languageCode");
        assertConfigurationFailure(
                directory,
                "enabled-missing-language.json",
                root,
                "CONFIG_REQUIRED_VALUE_MISSING"
        );

        speech.put("languageCode", "ru-RU");
        for (String requiredField : List.of(
                "provider",
                "languageCode",
                "voiceName",
                "audioEncoding",
                "speakingRate",
                "pitch",
                "volumeGainDb",
                "requestTimeoutMs",
                "alsoPrintToConsole"
        )) {
            ObjectNode missingRequired = root.deepCopy();
            ((ObjectNode) missingRequired.path("speech"))
                    .remove(requiredField);
            assertConfigurationFailure(
                    directory,
                    "missing-" + requiredField + ".json",
                    missingRequired,
                    "CONFIG_REQUIRED_VALUE_MISSING"
            );
        }

        speech.put("speakingRate", 2.01);
        assertConfigurationFailure(
                directory,
                "speaking-rate.json",
                root,
                "CONFIG_SPEECH_SPEAKING_RATE_INVALID"
        );

        speech.put("speakingRate", 1.0);
        speech.put("provider", "OTHER");
        assertConfigurationFailure(
                directory,
                "provider.json",
                root,
                "CONFIG_SPEECH_PROVIDER_INVALID"
        );

        speech.put("provider", "GOOGLE_CLOUD_TTS");
        speech.put("audioEncoding", "MP3");
        assertConfigurationFailure(
                directory,
                "encoding.json",
                root,
                "CONFIG_SPEECH_ENCODING_INVALID"
        );

        speech.put("audioEncoding", "LINEAR16");
        speech.put("pitch", 20.01);
        assertConfigurationFailure(
                directory,
                "pitch.json",
                root,
                "CONFIG_SPEECH_PITCH_INVALID"
        );

        speech.put("pitch", 0.0);
        speech.put("volumeGainDb", 16.01);
        assertConfigurationFailure(
                directory,
                "volume.json",
                root,
                "CONFIG_SPEECH_VOLUME_INVALID"
        );

        speech.put("volumeGainDb", 0.0);
        speech.put("requestTimeoutMs", 0);
        assertConfigurationFailure(
                directory,
                "timeout.json",
                root,
                "CONFIG_SPEECH_TIMEOUT_INVALID"
        );

        speech.put("requestTimeoutMs", 15_000);
        speech.put("outputDevice", " ");
        assertConfigurationFailure(
                directory,
                "output-device.json",
                root,
                "CONFIG_SPEECH_OUTPUT_DEVICE_INVALID"
        );

        speech.putNull("outputDevice");
        speech.put("unexpectedCredential", "must-be-rejected");
        assertConfigurationFailure(
                directory,
                "unknown.json",
                root,
                "CONFIG_JSON_INVALID"
        );

        speech.remove("unexpectedCredential");
        Path validEnabled = directory.resolve("valid-enabled.json");
        JSON.writeValue(validEnabled.toFile(), root);
        KaironConfiguration enabled =
                KaironConfiguration.load(validEnabled);
        assertEquals(
                googleSecret,
                enabled.googleCloudTextToSpeechApiKey()
        );
        assertFalse(enabled.toString().contains(googleSecret));

        Files.writeString(
                directory.resolve("authentication.json"),
                """
                        {
                          "llm": {"providers": {}},
                          "speech": {"googleCloudTts": null}
                        }
                        """,
                StandardCharsets.UTF_8
        );
        assertEquals(
                "AUTHENTICATION_GOOGLE_TTS_KEY_MISSING",
                assertThrows(
                        KaironConfiguration.ConfigurationException.class,
                        () -> KaironConfiguration.load(validEnabled)
                ).code()
        );
    }

    private static void assertConfigurationFailure(
            Path directory,
            String fileName,
            ObjectNode root,
            String expectedCode
    ) throws Exception {
        Path path = directory.resolve(fileName);
        JSON.writeValue(path.toFile(), root);
        assertEquals(
                expectedCode,
                assertThrows(
                        KaironConfiguration.ConfigurationException.class,
                        () -> KaironConfiguration.load(path)
                ).code()
        );
    }

    private static SpeechGateway speechGateway(
            RecordingSynthesis synthesis,
            RecordingAudioPlayer player,
            boolean alsoPrint
    ) {
        return new SpeechGateway(
                speechConfiguration(alsoPrint),
                synthesis,
                player,
                new ConsoleCommentSink(new PrintStream(
                        new ByteArrayOutputStream(),
                        true,
                        StandardCharsets.UTF_8
                ))
        );
    }

    private static SpeechConfiguration speechConfiguration(
            boolean alsoPrint
    ) {
        return new SpeechConfiguration(
                true,
                SpeechProvider.GOOGLE_CLOUD_TTS,
                "ru-RU",
                "ru-RU-Standard-A",
                SpeechAudioEncoding.LINEAR16,
                1.0,
                0.0,
                0.0,
                Duration.ofMillis(250),
                null,
                alsoPrint
        );
    }

    private static ObserverTurnCoordinator coordinator(
            Path trace,
            LlmClient llm,
            CommentSink sink
    ) {
        return coordinator(trace, llm, sink, 750L, 2_000L);
    }

    private static ObserverTurnCoordinator coordinator(
            Path trace,
            LlmClient llm,
            CommentSink sink,
            long quietPeriodMs,
            long maximumBatchAgeMs
    ) {
        return new ObserverTurnCoordinator(
                new ObserverConfiguration(
                        "ru",
                        quietPeriodMs,
                        maximumBatchAgeMs,
                        trace
                ),
                new LlmDecisionRequestCompactor(
                new LlmDecisionRequestFactory(),
                new JacksonDecisionRequestSerializer(),
                DecisionTurnPolicy.production()),
                new DecisionPromptFactory(),
                llm,
                sink,
                new JsonLinesTurnTraceWriter(trace)
        );
    }

    private static void completeReplayTurn(
            ObserverTurnCoordinator coordinator,
            long sequence
    ) throws Exception {
        coordinator.post(new ObserverCommand.QueueNewObservation(
                projected(observation(
                        sequence,
                        ObservationCaptureMode.REPLAY
                ))
        ));
        coordinator.post(new ObserverCommand.ReplaySourceExhausted(
                replayExhaustedSignal()
        ));
        coordinator.awaitIdle().toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
    }

    private static PublishedObservation<ScanOrganic> observation(
            long sequence,
            ObservationCaptureMode mode
    ) throws Exception {
        String raw = "{\"timestamp\":\"2026-07-28T12:00:00Z\","
                + "\"event\":\"ScanOrganic\",\"ScanType\":\"Log\","
                + "\"Genus_Localised\":\"Test genus " + sequence + "\","
                + "\"SystemAddress\":12345,\"Body\":20}";
        RawJournalData data = new RawJournalData(
                raw,
                JSON.readTree(raw),
                Optional.of("ScanOrganic"),
                Optional.of(Instant.parse("2026-07-28T12:00:00Z"))
        );
        return new PublishedObservation<>(
                "speech-observation-" + sequence,
                sequence + 1L,
                SOURCE,
                new JournalSourcePosition("Journal.speech.log", sequence),
                data.optionalJournalTimestamp(),
                Instant.parse("2026-07-28T12:00:01Z"),
                mode,
                JournalEventObservation.SCHEMA_VERSION,
                ScanOrganic.of(data)
        );
    }

    private static ProjectedObservation projected(
            PublishedObservation<? extends JournalEventObservation>
                    observation
    ) {
        CurrentGameStateProjection state =
                new CurrentGameStateProjector()
                        .applyAndCapture(observation);
        BehaviorGraphApplyResult graph =
                BehaviorGraphApplyResult.disabled(
                        observation.busSequence()
                );
        return new ProjectedObservation(
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
    }

    private static ObservationDraft<ScanOrganic> draft(
            long sequence,
            ObservationCaptureMode mode
    ) throws Exception {
        PublishedObservation<ScanOrganic> published =
                observation(sequence, mode);
        return new ObservationDraft<>(
                published.observationId(),
                published.source(),
                published.sourcePosition(),
                published.sourceTime(),
                published.observedAt(),
                published.captureMode(),
                published.schemaVersion(),
                published.payload()
        );
    }

    private static PublishedObservation<ObservationSourceSignal>
    replayExhaustedSignal() {
        return new PublishedObservation<>(
                "speech-replay-exhausted",
                10_000L,
                SOURCE,
                new JournalSourcePosition("Journal.speech.log", 10_000L),
                Optional.empty(),
                Instant.parse("2026-07-28T12:00:02Z"),
                ObservationCaptureMode.REPLAY,
                ObservationSourceSignal.SCHEMA_VERSION,
                new ObservationSourceSignal(
                        ObservationSourceSignalType.REPLAY_SOURCE_EXHAUSTED
                )
        );
    }

    private static ObservationDraft<ObservationSourceSignal>
    replayExhaustedDraft() {
        PublishedObservation<ObservationSourceSignal> published =
                replayExhaustedSignal();
        return new ObservationDraft<>(
                published.observationId(),
                published.source(),
                published.sourcePosition(),
                published.sourceTime(),
                published.observedAt(),
                published.captureMode(),
                published.schemaVersion(),
                published.payload()
        );
    }

    /** A decision and a sentence: the whole of the response contract. */
    private static String commentResponse(String text) {
        return "{\"decision\":\"COMMENT\",\"comment\":"
                + '"' + text + '"'
                + "}";
    }

    private static JsonNode firstTrace(Path trace) throws Exception {
        return JSON.readTree(Files.readAllLines(
                trace,
                StandardCharsets.UTF_8
        ).getFirst());
    }

    private static final class FixedLlmClient implements LlmClient {

        private final String response;

        private FixedLlmClient(String response) {
            this.response = response;
        }

        @Override
        public CompletionStage<LlmResponse> complete(
                ModelInput exactModelInput
        ) {
            return CompletableFuture.completedFuture(
                    new LlmResponse(response, 7L)
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

    private static final class RecordingSynthesis
            implements SpeechSynthesisClient {

        private final byte[] response;
        private final List<String> texts = new ArrayList<>();
        private final List<String> threadNames = new ArrayList<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final CountDownLatch release;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean cancellationRequested =
                new AtomicBoolean();
        private final AtomicBoolean synthesizing = new AtomicBoolean();
        private final AtomicInteger cancellationCount = new AtomicInteger();
        private boolean ignoreCancellation;
        private SpeechSynthesisException checkedFailure;
        private RuntimeException runtimeFailure;

        private RecordingSynthesis(byte[] response) {
            this(response, null);
        }

        private RecordingSynthesis(
                byte[] response,
                CountDownLatch release
        ) {
            this.response = Arrays.copyOf(response, response.length);
            this.release = release;
        }

        private static RecordingSynthesis blocking(byte[] response) {
            return new RecordingSynthesis(response, new CountDownLatch(1));
        }

        @Override
        public synchronized byte[] synthesize(String text)
                throws SpeechSynthesisException {
            synthesizing.set(true);
            texts.add(text);
            threadNames.add(Thread.currentThread().getName());
            entered.countDown();
            try {
                if (release != null) {
                    boolean waiting = true;
                    while (waiting) {
                        try {
                            release.await();
                            waiting = false;
                        } catch (InterruptedException interrupted) {
                            if (!ignoreCancellation) {
                                cancellationRequested.set(false);
                                Thread.currentThread().interrupt();
                                throw new SpeechSynthesisException(
                                        SpeechFailureCategory.CANCELLED
                                );
                            }
                        }
                    }
                }
                if (cancellationRequested.getAndSet(false)) {
                    throw new SpeechSynthesisException(
                            SpeechFailureCategory.CANCELLED
                    );
                }
                if (checkedFailure != null) {
                    throw checkedFailure;
                }
                if (runtimeFailure != null) {
                    throw runtimeFailure;
                }
                return Arrays.copyOf(response, response.length);
            } finally {
                synthesizing.set(false);
                finished.countDown();
            }
        }

        private synchronized int callCount() {
            return texts.size();
        }

        private synchronized List<String> texts() {
            return List.copyOf(texts);
        }

        private synchronized List<String> threadNames() {
            return List.copyOf(threadNames);
        }

        @Override
        public void cancelCurrentSynthesis() {
            if (!synthesizing.get()) {
                return;
            }
            cancellationCount.incrementAndGet();
            cancellationRequested.set(true);
            if (release != null && !ignoreCancellation) {
                release.countDown();
            }
        }

        @Override
        public void close() {
            closed.set(true);
            if (release != null && !ignoreCancellation) {
                release.countDown();
            }
        }
    }

    private static final class RecordingAudioPlayer implements AudioPlayer {

        private final List<byte[]> audio = new ArrayList<>();
        private final List<String> threadNames = new ArrayList<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean cancellationRequested =
                new AtomicBoolean();
        private final AtomicInteger cancellationCount = new AtomicInteger();
        private final AtomicInteger concurrent = new AtomicInteger();
        private final AtomicInteger maximumConcurrent = new AtomicInteger();
        private AudioPlaybackException checkedFailure;
        private long playDelayMs;
        private boolean throwOnClose;

        private RecordingAudioPlayer() {
            this(null);
        }

        private RecordingAudioPlayer(CountDownLatch release) {
            this.release = release;
        }

        private static RecordingAudioPlayer blocking() {
            return new RecordingAudioPlayer(new CountDownLatch(1));
        }

        @Override
        public void play(byte[] wavAudio, String outputDevice)
                throws AudioPlaybackException {
            cancellationRequested.set(false);
            int active = concurrent.incrementAndGet();
            maximumConcurrent.accumulateAndGet(active, Math::max);
            synchronized (this) {
                audio.add(Arrays.copyOf(wavAudio, wavAudio.length));
                threadNames.add(Thread.currentThread().getName());
            }
            entered.countDown();
            try {
                if (release != null) {
                    release.await();
                }
                if (playDelayMs > 0L) {
                    Thread.sleep(playDelayMs);
                }
                if (cancellationRequested.getAndSet(false)) {
                    throw new AudioPlaybackException(
                            SpeechFailureCategory.CANCELLED
                    );
                }
                if (checkedFailure != null) {
                    throw checkedFailure;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AudioPlaybackException(
                        SpeechFailureCategory.CANCELLED
                );
            } finally {
                concurrent.decrementAndGet();
            }
        }

        private synchronized int callCount() {
            return audio.size();
        }

        private synchronized List<byte[]> audio() {
            return audio.stream()
                    .map(value -> Arrays.copyOf(value, value.length))
                    .toList();
        }

        private synchronized List<String> threadNames() {
            return List.copyOf(threadNames);
        }

        @Override
        public void cancelCurrentPlayback() {
            if (concurrent.get() == 0) {
                return;
            }
            cancellationCount.incrementAndGet();
            cancellationRequested.set(true);
            if (release != null) {
                release.countDown();
            }
        }

        @Override
        public void close() {
            closed.set(true);
            if (release != null) {
                release.countDown();
            }
            if (throwOnClose) {
                throw new IllegalStateException(
                        "simulated audio close failure"
                );
            }
        }
    }
}
