package kairon.output;

import kairon.config.KaironConfiguration.SpeechConfiguration;
import kairon.speech.AudioPlayer;
import kairon.speech.AudioPlayer.AudioPlaybackException;
import kairon.speech.SpeechSynthesisClient;
import kairon.speech.SpeechSynthesisClient.SpeechFailureCategory;
import kairon.speech.SpeechSynthesisClient.SpeechSynthesisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Single speech-admission gateway for validated comments. It owns FIFO
 * queueing, serial synthesis/playback, request-scoped cancellation, and
 * shutdown cancellation. Console output, synthesis and local playback execute
 * away from observation and coordinator threads.
 */
public final class SpeechGateway implements CommentSink {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SpeechGateway.class);

    private final SpeechSynthesisClient synthesisClient;
    private final AudioPlayer audioPlayer;
    private final ConsoleCommentSink consoleSink;
    private final boolean alsoPrintToConsole;
    private final String outputDevice;
    private final SpeechDescriptor descriptor;
    private final long shutdownWaitMillis;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, DeliveryTask> requests =
            new ConcurrentHashMap<>();
    private final AtomicReference<DeliveryTask> activeTask =
            new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SpeechGateway(
            SpeechConfiguration configuration,
            SpeechSynthesisClient synthesisClient,
            AudioPlayer audioPlayer,
            ConsoleCommentSink consoleSink
    ) {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.enabled()) {
            throw new IllegalArgumentException(
                    "SpeechGateway requires enabled speech"
            );
        }
        this.synthesisClient = Objects.requireNonNull(
                synthesisClient,
                "synthesisClient"
        );
        this.audioPlayer = Objects.requireNonNull(audioPlayer, "audioPlayer");
        this.consoleSink = Objects.requireNonNull(consoleSink, "consoleSink");
        this.alsoPrintToConsole = configuration.alsoPrintToConsole();
        this.outputDevice = configuration.outputDevice();
        this.descriptor = new SpeechDescriptor(
                true,
                configuration.provider().name(),
                configuration.voiceName()
        );
        this.shutdownWaitMillis = Math.min(
                5_000L,
                Math.max(
                        1_000L,
                        configuration.requestTimeout().toMillis()
                )
        );
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "speech-output");
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    @Override
    public CompletionStage<CommentDeliveryResult> deliver(String comment) {
        return submit(new SpeechRequest(
                UUID.randomUUID().toString(),
                comment
        )).completion();
    }

    /**
     * Submits one correlated utterance and returns its request-scoped handle.
     * Cancelling the handle removes queued work or stops only that request.
     */
    public SpeechHandle submit(SpeechRequest request) {
        Objects.requireNonNull(request, "request");
        DeliveryTask task = new DeliveryTask(request);
        if (requests.putIfAbsent(request.requestId(), task) != null) {
            throw new IllegalArgumentException(
                    "speech requestId must be unique while active"
            );
        }
        SpeechHandle handle = new SpeechHandle(
                request.requestId(),
                task.result.minimalCompletionStage(),
                task::requestCancellation
        );
        if (closed.get()) {
            task.requestCancellation();
            requests.remove(request.requestId(), task);
            return handle;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejection) {
            task.requestCancellation();
            requests.remove(request.requestId(), task);
        }
        return handle;
    }

    /**
     * Cancels the matching queued or active request, if it still belongs to
     * this gateway.
     */
    public boolean cancel(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException(
                    "speech requestId must be nonblank"
            );
        }
        DeliveryTask task = requests.get(requestId);
        return task != null && task.requestCancellation();
    }

    @Override
    public SpeechDescriptor speechDescriptor() {
        return descriptor;
    }

    /**
     * Diagnostic state only; it never influences observer semantics.
     */
    public SpeechOutcome currentState() {
        DeliveryTask task = activeTask.get();
        return task == null ? SpeechOutcome.NOT_REQUESTED : task.state;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Runnable> queued = executor.shutdownNow();
        requests.values().forEach(DeliveryTask::requestCancellation);
        for (Runnable runnable : queued) {
            if (runnable instanceof DeliveryTask task) {
                task.requestCancellation();
                requests.remove(task.request.requestId(), task);
            }
        }
        closeResource("AUDIO_PLAYER_CLOSE_FAILED", audioPlayer);
        closeResource("SPEECH_SYNTHESIS_CLIENT_CLOSE_FAILED", synthesisClient);
        try {
            if (!executor.awaitTermination(
                    shutdownWaitMillis,
                    TimeUnit.MILLISECONDS
            )) {
                LOGGER.error("SPEECH_OUTPUT_SHUTDOWN_TIMEOUT");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeResource(String code, AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception failure) {
            LOGGER.error(
                    "{} cause={}",
                    code,
                    failure.getClass().getSimpleName()
            );
        }
    }

    private final class DeliveryTask implements Runnable {

        private final SpeechRequest request;
        private final CompletableFuture<CommentDeliveryResult> result =
                new CompletableFuture<>();
        private volatile SpeechOutcome state = SpeechOutcome.NOT_REQUESTED;
        private volatile Thread runnerThread;
        private boolean cancellationRequested;
        private ConsoleOutcome consoleOutcome = ConsoleOutcome.NOT_ATTEMPTED;
        private Instant synthesisStartedAt;
        private Instant synthesisCompletedAt;
        private Instant playbackStartedAt;
        private Instant playbackCompletedAt;

        private DeliveryTask(SpeechRequest request) {
            this.request = request;
        }

        @Override
        public void run() {
            if (closed.get()
                    || result.isDone()
                    || !activeTask.compareAndSet(null, this)) {
                requestCancellation();
                requests.remove(request.requestId(), this);
                return;
            }
            runnerThread = Thread.currentThread();
            try {
                if (closed.get() || result.isDone()) {
                    requestCancellation();
                    return;
                }
                final ConsoleOutcome observedConsoleOutcome;
                if (alsoPrintToConsole) {
                    observedConsoleOutcome =
                            consoleSink.deliverNow(request.text())
                            ? ConsoleOutcome.DELIVERED
                            : ConsoleOutcome.FAILED;
                } else {
                    observedConsoleOutcome = ConsoleOutcome.SKIPPED;
                }
                if (!recordConsoleOutcome(observedConsoleOutcome)
                        || !beginSynthesis()) {
                    return;
                }

                final byte[] synthesized;
                try {
                    synthesized = synthesisClient.synthesize(request.text());
                } catch (SpeechSynthesisException failure) {
                    if (!finishSynthesisPhase()) {
                        return;
                    }
                    fail(
                            SpeechOutcome.SYNTHESIS_FAILED,
                            failure.category(),
                            failure
                    );
                    return;
                } catch (RuntimeException failure) {
                    if (!finishSynthesisPhase()) {
                        return;
                    }
                    fail(
                            SpeechOutcome.SYNTHESIS_FAILED,
                            SpeechFailureCategory.INTERNAL,
                            failure
                    );
                    return;
                }
                if (!finishSynthesisPhase()) {
                    return;
                }
                if (synthesized == null || synthesized.length == 0) {
                    fail(
                            SpeechOutcome.SYNTHESIS_FAILED,
                            SpeechFailureCategory.SYNTHESIS_RESPONSE,
                            null
                    );
                    return;
                }
                if (!queueForPlayback()) {
                    return;
                }

                byte[] wavAudio = Arrays.copyOf(
                        synthesized,
                        synthesized.length
                );
                if (!beginPlayback()) {
                    return;
                }
                try {
                    audioPlayer.play(wavAudio, outputDevice);
                } catch (AudioPlaybackException failure) {
                    if (!finishPlaybackPhase()) {
                        return;
                    }
                    fail(
                            SpeechOutcome.PLAYBACK_FAILED,
                            failure.category(),
                            failure
                    );
                    return;
                } catch (RuntimeException failure) {
                    if (!finishPlaybackPhase()) {
                        return;
                    }
                    fail(
                            SpeechOutcome.PLAYBACK_FAILED,
                            SpeechFailureCategory.INTERNAL,
                            failure
                    );
                    return;
                }
                if (!finishPlaybackPhase()) {
                    return;
                }
                completeTerminal(
                        SpeechOutcome.DELIVERED,
                        SpeechFailureCategory.NONE
                );
            } finally {
                activeTask.compareAndSet(this, null);
                runnerThread = null;
                requests.remove(request.requestId(), this);
            }
        }

        private synchronized boolean recordConsoleOutcome(
                ConsoleOutcome outcome
        ) {
            if (terminalOrCancelled()) {
                return false;
            }
            consoleOutcome = outcome;
            return true;
        }

        private synchronized boolean beginSynthesis() {
            if (terminalOrCancelled()) {
                return false;
            }
            state = SpeechOutcome.SYNTHESIZING;
            synthesisStartedAt = Instant.now();
            return true;
        }

        private synchronized boolean finishSynthesisPhase() {
            if (terminalOrCancelled()) {
                return false;
            }
            synthesisCompletedAt = Instant.now();
            return true;
        }

        private synchronized boolean queueForPlayback() {
            if (terminalOrCancelled()) {
                return false;
            }
            state = SpeechOutcome.QUEUED_FOR_PLAYBACK;
            return true;
        }

        private synchronized boolean beginPlayback() {
            if (terminalOrCancelled()) {
                return false;
            }
            state = SpeechOutcome.PLAYING;
            playbackStartedAt = Instant.now();
            return true;
        }

        private synchronized boolean finishPlaybackPhase() {
            if (terminalOrCancelled()) {
                return false;
            }
            playbackCompletedAt = Instant.now();
            return true;
        }

        private boolean terminalOrCancelled() {
            return cancellationRequested || result.isDone();
        }

        private void fail(
                SpeechOutcome outcome,
                SpeechFailureCategory category,
                Throwable failure
        ) {
            if (category == SpeechFailureCategory.CANCELLED) {
                requestCancellation();
                return;
            }
            if (!completeTerminal(outcome, category)) {
                return;
            }
            String cause = failure == null
                    ? "<none>"
                    : failure.getClass().getSimpleName();
            LOGGER.error(
                    "SPEECH_COMMENT_DELIVERY_FAILED outcome={} "
                            + "category={} cause={}",
                    outcome,
                    category,
                    cause
            );
        }

        private synchronized boolean completeTerminal(
                SpeechOutcome outcome,
                SpeechFailureCategory category
        ) {
            if (result.isDone()) {
                return false;
            }
            state = outcome;
            result.complete(new CommentDeliveryResult(
                    descriptor,
                    consoleOutcome,
                    new SpeechDeliveryResult(
                            outcome,
                            category,
                            synthesisStartedAt,
                            synthesisCompletedAt,
                            playbackStartedAt,
                            playbackCompletedAt
                    )
            ));
            return true;
        }

        private boolean requestCancellation() {
            final Thread runner;
            final SpeechOutcome interruptedPhase;
            synchronized (this) {
                if (result.isDone()) {
                    return false;
                }
                cancellationRequested = true;
                interruptedPhase = state;
                completeTerminal(
                        SpeechOutcome.CANCELLED,
                        SpeechFailureCategory.CANCELLED
                );
                runner = runnerThread;
            }

            boolean removedFromQueue = executor.remove(this);
            if (runner != null || activeTask.get() == this) {
                if (interruptedPhase == SpeechOutcome.SYNTHESIZING) {
                    synthesisClient.cancelCurrentSynthesis();
                } else if (interruptedPhase
                        == SpeechOutcome.QUEUED_FOR_PLAYBACK
                        || interruptedPhase == SpeechOutcome.PLAYING) {
                    audioPlayer.cancelCurrentPlayback();
                }
                if (runner != null && runner != Thread.currentThread()) {
                    runner.interrupt();
                }
            }
            if (removedFromQueue || runner == null) {
                requests.remove(request.requestId(), this);
            }
            return true;
        }
    }

    /**
     * Immutable correlated input accepted by the speech gateway.
     */
    public record SpeechRequest(String requestId, String text) {

        public SpeechRequest {
            if (requestId == null || requestId.isBlank()) {
                throw new IllegalArgumentException(
                        "speech requestId must be nonblank"
                );
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException(
                        "speech text must be nonblank"
                );
            }
        }
    }

    /**
     * Request-scoped completion and cancellation boundary.
     */
    public static final class SpeechHandle {

        private final String requestId;
        private final CompletionStage<CommentDeliveryResult> completion;
        private final BooleanSupplier cancellation;

        private SpeechHandle(
                String requestId,
                CompletionStage<CommentDeliveryResult> completion,
                BooleanSupplier cancellation
        ) {
            this.requestId = requestId;
            this.completion = completion;
            this.cancellation = cancellation;
        }

        public String requestId() {
            return requestId;
        }

        public CompletionStage<CommentDeliveryResult> completion() {
            return completion;
        }

        public boolean cancel() {
            return cancellation.getAsBoolean();
        }
    }
}
