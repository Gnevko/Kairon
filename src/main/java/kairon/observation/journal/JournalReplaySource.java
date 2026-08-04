package kairon.observation.journal;

import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.bus.ObservationBus;
import kairon.observation.bus.ObservationBus.PublishReceipt;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.JournalParseFailure;
import kairon.observation.journal.JournalLineParser.JournalParseResult;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter.ExactDuplicateJournalObservationException;
import kairon.observation.journal.JournalObservationAdapter.JournalSourcePosition;
import kairon.observation.journal.PollingJournalTailReader.SubscriberHandlerFailure;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.source.ObservationSourceSignal.ObservationSourceSignalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Replays one finite journal file through the same bus path used by live
 * observation. Consecutive successfully published records retain their
 * positive journal-time gap, capped at six seconds, before a typed
 * replay-exhaustion signal is published.
 */
public final class JournalReplaySource implements AutoCloseable {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(JournalReplaySource.class);
    private static final byte[] SIGNAL_ID_DOMAIN =
            "kairon-observation-source-signal-v1\0".getBytes(StandardCharsets.UTF_8);
    private static final Duration MAXIMUM_INTER_EVENT_PAUSE =
            Duration.ofSeconds(6);
    private final Path journalFile;
    private final JournalLineParser parser;
    private final JournalObservationAdapter adapter;
    private final ObservationBus bus;
    private final Clock clock;
    private final ReplayDelay replayDelay;
    private final ExecutorService sourceExecutor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicReference<Thread> sourceThread =
            new AtomicReference<>();

    public JournalReplaySource(
            Path journalFile,
            JournalLineParser parser,
            JournalObservationAdapter adapter,
            ObservationBus bus
    ) {
        this(
                journalFile,
                parser,
                adapter,
                bus,
                Clock.systemUTC(),
                JournalReplaySource::pauseCurrentThread
        );
    }

    public JournalReplaySource(
            Path journalFile,
            JournalLineParser parser,
            JournalObservationAdapter adapter,
            ObservationBus bus,
            Clock clock
    ) {
        this(
                journalFile,
                parser,
                adapter,
                bus,
                clock,
                JournalReplaySource::pauseCurrentThread
        );
    }

    JournalReplaySource(
            Path journalFile,
            JournalLineParser parser,
            JournalObservationAdapter adapter,
            ObservationBus bus,
            Clock clock,
            ReplayDelay replayDelay
    ) {
        this.journalFile =
                Objects.requireNonNull(journalFile, "journalFile")
                        .toAbsolutePath()
                        .normalize();
        this.parser = Objects.requireNonNull(parser, "parser");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.replayDelay = Objects.requireNonNull(
                replayDelay,
                "replayDelay"
        );
        this.sourceExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "journal-replay-source");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Publishes all complete valid records sequentially, waits their receipts,
     * then publishes and waits for {@code REPLAY_SOURCE_EXHAUSTED}.
     */
    public CompletionStage<ReplayReport> publishAll() {
        if (stopRequested.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("replay source is stopped")
            );
        }
        if (!started.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("replay may run exactly once")
            );
        }
        try {
            return CompletableFuture.supplyAsync(
                    this::runOnSourceThread,
                    sourceExecutor
            );
        } catch (RejectedExecutionException rejection) {
            return CompletableFuture.failedFuture(rejection);
        }
    }

    /**
     * Requests bounded replay cancellation. An active journal-time pause is
     * interrupted; already accepted bus publications retain their normal
     * receipts and source identities.
     */
    public boolean requestStop() {
        if (!stopRequested.compareAndSet(false, true)) {
            return false;
        }
        sourceExecutor.shutdown();
        Thread running = sourceThread.get();
        if (running != null) {
            running.interrupt();
        }
        return true;
    }

    @Override
    public void close() {
        requestStop();
    }

    public static String replayExhaustedObservationId(
            String sourceInstanceId,
            String journalBasename,
            long fileSize
    ) {
        Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
        Objects.requireNonNull(journalBasename, "journalBasename");
        if (fileSize < 0) {
            throw new IllegalArgumentException("fileSize must be nonnegative");
        }
        MessageDigest digest = sha256();
        digest.update(SIGNAL_ID_DOMAIN);
        digest.update(sourceInstanceId.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(
                ObservationSourceSignalType.REPLAY_SOURCE_EXHAUSTED
                        .name()
                        .getBytes(StandardCharsets.UTF_8)
        );
        digest.update((byte) 0);
        digest.update(journalBasename.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(fileSize).getBytes(StandardCharsets.UTF_8));
        return "os1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    }

    private ReplayReport runOnSourceThread() {
        Thread running = Thread.currentThread();
        sourceThread.set(running);
        try {
            return publishAllOnSourceThread();
        } finally {
            sourceThread.compareAndSet(running, null);
            sourceExecutor.shutdown();
        }
    }

    private ReplayReport publishAllOnSourceThread() {
        int completeRecordCount = 0;
        int publishedRecordCount = 0;
        long highWater = 0;
        List<SubscriberHandlerFailure> handlerFailures = new ArrayList<>();
        String failedObservationId = null;
        Throwable failure = null;
        Optional<Instant> previousPublishedTimestamp = Optional.empty();

        final long fileSize;
        final byte[] bytes;
        try {
            fileSize = Files.size(journalFile);
            bytes = readRange(journalFile, 0, fileSize);
        } catch (Exception exception) {
            return new ReplayReport(
                    completeRecordCount,
                    publishedRecordCount,
                    OptionalLong.empty(),
                    false,
                    List.of(),
                    Optional.empty(),
                    Optional.of(unwrap(exception)),
                    false,
                    false
            );
        }

        if (stopRequested.get()) {
            return cancelledReport(
                    completeRecordCount,
                    publishedRecordCount,
                    highWater,
                    handlerFailures
            );
        }

        String basename = journalFile.getFileName().toString();
        int recordStart = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] != '\n') {
                continue;
            }
            if (stopRequested.get()) {
                return cancelledReport(
                        completeRecordCount,
                        publishedRecordCount,
                        highWater,
                        handlerFailures
                );
            }
            completeRecordCount++;
            int contentEnd = index;
            if (contentEnd > recordStart && bytes[contentEnd - 1] == '\r') {
                contentEnd--;
            }
            JournalParseResult result = parser.parse(new CompleteJournalRecord(
                    basename,
                    recordStart,
                    Arrays.copyOfRange(bytes, recordStart, contentEnd)
            ));
            if (result instanceof ParsedJournalRecord parsed) {
                try {
                    pauseBetween(
                            previousPublishedTimestamp,
                            parsed.optionalJournalTimestamp(),
                            parsed
                    );
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    if (stopRequested.get()) {
                        return cancelledReport(
                                completeRecordCount,
                                publishedRecordCount,
                                highWater,
                                handlerFailures
                        );
                    }
                    failedObservationId =
                            JournalObservationAdapter.journalObservationId(
                                    parsed.journalBasename(),
                                    parsed.zeroBasedSourceByteOffset()
                            );
                    failure = interrupted;
                    break;
                }
                if (stopRequested.get()) {
                    return cancelledReport(
                            completeRecordCount,
                            publishedRecordCount,
                            highWater,
                            handlerFailures
                    );
                }
                ObservationDraft<JournalEventObservation> draft = null;
                try {
                    draft = adapter.adapt(
                            parsed,
                            ObservationCaptureMode.REPLAY,
                            clock.instant()
                    );
                    PublishReceipt receipt =
                            bus.publish(draft).toCompletableFuture().join();
                    adapter.commit(draft.observationId());
                    publishedRecordCount++;
                    highWater = Math.max(highWater, receipt.busSequence());
                    for (String subscriberId : receipt.failedSubscriberIds()) {
                        handlerFailures.add(new SubscriberHandlerFailure(
                                subscriberId,
                                receipt.observationId(),
                                receipt.busSequence()
                        ));
                    }
                    previousPublishedTimestamp =
                            parsed.optionalJournalTimestamp();
                } catch (RuntimeException exception) {
                    if (draft == null
                            && exception instanceof ExactDuplicateJournalObservationException) {
                        LOGGER.warn(
                                "EXACT_SOURCE_DUPLICATE observationId={} basename={} offset={}",
                                JournalObservationAdapter.journalObservationId(
                                        parsed.journalBasename(),
                                        parsed.zeroBasedSourceByteOffset()
                                ),
                                parsed.journalBasename(),
                                parsed.zeroBasedSourceByteOffset()
                        );
                        recordStart = index + 1;
                        continue;
                    }
                    if (draft != null) {
                        adapter.rollback(draft.observationId());
                        failedObservationId = draft.observationId();
                    } else {
                        failedObservationId = JournalObservationAdapter.journalObservationId(
                                parsed.journalBasename(),
                                parsed.zeroBasedSourceByteOffset()
                        );
                    }
                    failure = unwrap(exception);
                    break;
                }
            } else {
                diagnoseParseFailure((JournalParseFailure) result);
                previousPublishedTimestamp = Optional.empty();
            }
            recordStart = index + 1;
        }

        if (stopRequested.get()) {
            return cancelledReport(
                    completeRecordCount,
                    publishedRecordCount,
                    highWater,
                    handlerFailures
            );
        }

        if (failure == null && recordStart < bytes.length) {
            LOGGER.warn(
                    "REPLAY_INCOMPLETE_TAIL_IGNORED basename={} "
                            + "zeroBasedSourceByteOffset={} byteCount={}",
                    basename,
                    recordStart,
                    bytes.length - recordStart
            );
        }

        if (failure != null) {
            return new ReplayReport(
                    completeRecordCount,
                    publishedRecordCount,
                    optionalHighWater(highWater),
                    false,
                    List.copyOf(handlerFailures),
                    Optional.ofNullable(failedObservationId),
                    Optional.of(failure),
                    false,
                    false
            );
        }

        if (stopRequested.get()) {
            return cancelledReport(
                    completeRecordCount,
                    publishedRecordCount,
                    highWater,
                    handlerFailures
            );
        }

        ObservationDraft<ObservationSourceSignal> signalDraft =
                replayExhaustedDraft(basename, fileSize);
        try {
            PublishReceipt receipt = bus.publish(signalDraft).toCompletableFuture().join();
            highWater = Math.max(highWater, receipt.busSequence());
            for (String subscriberId : receipt.failedSubscriberIds()) {
                handlerFailures.add(new SubscriberHandlerFailure(
                        subscriberId,
                        receipt.observationId(),
                        receipt.busSequence()
                ));
            }
        } catch (RuntimeException exception) {
            failedObservationId = signalDraft.observationId();
            failure = unwrap(exception);
            LOGGER.error(
                    "REPLAY_SOURCE_EXHAUSTED_PUBLICATION_FAILED observationId={}",
                    failedObservationId,
                    failure
            );
        }

        boolean successful = failure == null;
        return new ReplayReport(
                completeRecordCount,
                publishedRecordCount,
                optionalHighWater(highWater),
                failure == null,
                List.copyOf(handlerFailures),
                Optional.ofNullable(failedObservationId),
                Optional.ofNullable(failure),
                successful,
                false
        );
    }

    private void pauseBetween(
            Optional<Instant> previousTimestamp,
            Optional<Instant> currentTimestamp,
            ParsedJournalRecord currentRecord
    ) throws InterruptedException {
        if (previousTimestamp.isEmpty() || currentTimestamp.isEmpty()) {
            return;
        }
        Instant previous = previousTimestamp.orElseThrow();
        Instant current = currentTimestamp.orElseThrow();
        if (!current.isAfter(previous)) {
            LOGGER.debug(
                    "REPLAY_TIMESTAMP_NON_INCREASING basename={} "
                            + "zeroBasedSourceByteOffset={} previous={} current={}",
                    currentRecord.journalBasename(),
                    currentRecord.zeroBasedSourceByteOffset(),
                    previous,
                    current
            );
            return;
        }
        Duration sourceGap = Duration.between(previous, current);
        Duration pause = sourceGap.compareTo(MAXIMUM_INTER_EVENT_PAUSE) > 0
                ? MAXIMUM_INTER_EVENT_PAUSE
                : sourceGap;
        replayDelay.pause(pause);
    }

    private ReplayReport cancelledReport(
            int completeRecordCount,
            int publishedRecordCount,
            long highWater,
            List<SubscriberHandlerFailure> handlerFailures
    ) {
        return new ReplayReport(
                completeRecordCount,
                publishedRecordCount,
                optionalHighWater(highWater),
                false,
                List.copyOf(handlerFailures),
                Optional.empty(),
                Optional.empty(),
                false,
                true
        );
    }

    private static void pauseCurrentThread(Duration duration)
            throws InterruptedException {
        TimeUnit.NANOSECONDS.sleep(duration.toNanos());
    }

    private ObservationDraft<ObservationSourceSignal> replayExhaustedDraft(
            String basename,
            long fileSize
    ) {
        ObservationSourceSignal payload = new ObservationSourceSignal(
                ObservationSourceSignalType.REPLAY_SOURCE_EXHAUSTED
        );
        String observationId = replayExhaustedObservationId(
                adapter.source().sourceInstanceId(),
                basename,
                fileSize
        );
        Instant observedAt = clock.instant();
        return new ObservationDraft<>(
                observationId,
                adapter.source(),
                new JournalSourcePosition(basename, fileSize),
                Optional.empty(),
                observedAt,
                ObservationCaptureMode.REPLAY,
                ObservationSourceSignal.SCHEMA_VERSION,
                payload
        );
    }

    private static void diagnoseParseFailure(JournalParseFailure failure) {
        LOGGER.warn(
                "JOURNAL_RECORD_REJECTED basename={} zeroBasedSourceByteOffset={} kind={} "
                        + "diagnostic={}",
                failure.journalBasename(),
                failure.zeroBasedSourceByteOffset(),
                failure.kind(),
                failure.diagnostic()
        );
    }

    private static byte[] readRange(Path file, long startInclusive, long endExclusive)
            throws IOException {
        long expected = endExclusive - startInclusive;
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(expected, 8192)
        );
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(startInclusive);
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long remaining = expected;
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = channel.read(buffer);
                if (read < 0) {
                    throw new EOFException("replay journal shrank during a bounded read");
                }
                if (read == 0) {
                    continue;
                }
                output.write(buffer.array(), 0, read);
                remaining -= read;
            }
        }
        return output.toByteArray();
    }

    private static OptionalLong optionalHighWater(long highWater) {
        return highWater == 0 ? OptionalLong.empty() : OptionalLong.of(highWater);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }

    private static Throwable unwrap(Throwable exception) {
        Throwable current = exception;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Replay transport report. {@code successful} covers normal receipts for
     * all attempted non-duplicate journal drafts and the exhaustion draft. A
     * normal receipt can still list isolated handler failures, which lifecycle
     * wiring evaluates by ID.
     */
    public record ReplayReport(
            int completeRecordCount,
            int publishedRecordCount,
            OptionalLong acceptedHighWaterBusSequence,
            boolean exhaustionSignalAccepted,
            List<SubscriberHandlerFailure> handlerFailures,
            Optional<String> failedObservationId,
            Optional<Throwable> failure,
            boolean successful,
            boolean cancelled
    ) {

        public ReplayReport {
            acceptedHighWaterBusSequence =
                    Objects.requireNonNull(
                            acceptedHighWaterBusSequence,
                            "acceptedHighWaterBusSequence"
                    );
            handlerFailures = List.copyOf(
                    Objects.requireNonNull(handlerFailures, "handlerFailures")
            );
            failedObservationId =
                    Objects.requireNonNull(failedObservationId, "failedObservationId");
            failure = Objects.requireNonNull(failure, "failure");
            if (successful && cancelled) {
                throw new IllegalArgumentException(
                        "replay cannot be successful and cancelled"
                );
            }
            if (cancelled && failure.isPresent()) {
                throw new IllegalArgumentException(
                        "cancelled replay must not report a source failure"
                );
            }
        }

        public boolean handlerFailed(String subscriberId) {
            Objects.requireNonNull(subscriberId, "subscriberId");
            return handlerFailures.stream()
                    .anyMatch(handlerFailure ->
                            handlerFailure.subscriberId().equals(subscriberId));
        }
    }

    @FunctionalInterface
    interface ReplayDelay {

        void pause(Duration duration) throws InterruptedException;
    }
}
