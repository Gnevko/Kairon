package kairon.observation.status;

import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.bus.ObservationBus;
import kairon.observation.bus.ObservationBus.PublishReceipt;
import kairon.observation.status.StatusSnapshotParser.ParsedStatusSnapshot;
import kairon.observation.status.StatusSnapshotParser.StatusParseFailure;
import kairon.observation.status.StatusSnapshotParser.StatusParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls the whole-file-replacement {@code Status.json} source and publishes
 * immutable snapshots through {@link ObservationBus}.
 *
 * <p>Missing, temporarily unreadable, and malformed replacements are
 * diagnosed and retried. They never replace the last successfully accepted
 * source content. The source waits for each bus receipt before committing its
 * sequence, so later polls cannot overtake an accepted snapshot.</p>
 */
public final class PollingStatusWatcher implements AutoCloseable {

    public static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PollingStatusWatcher.class);
    private static final Duration TRANSIENT_DIAGNOSTIC_INTERVAL =
            Duration.ofSeconds(5);

    private final Path statusFile;
    private final StatusSnapshotParser parser;
    private final StatusObservationAdapter adapter;
    private final ObservationBus bus;
    private final Clock clock;
    private final Duration pollInterval;
    private final ScheduledExecutorService sourceExecutor;
    private final List<SubscriberHandlerFailure> handlerFailures =
            new ArrayList<>();
    private final CompletableFuture<Throwable> terminalFailureStage =
            new CompletableFuture<>();

    private volatile Lifecycle lifecycle = Lifecycle.NEW;
    private ScheduledFuture<?> pollingTask;
    private CompletableFuture<StatusStopReport> stopStage;
    private byte[] committedContent;
    private long nextSnapshotSequence;
    private long acceptedHighWaterBusSequence;
    private Throwable terminalFailure;
    private Instant nextTransientDiagnosticAt = Instant.MIN;

    public PollingStatusWatcher(
            Path statusFile,
            StatusSnapshotParser parser,
            StatusObservationAdapter adapter,
            ObservationBus bus
    ) {
        this(
                statusFile,
                parser,
                adapter,
                bus,
                Clock.systemUTC(),
                POLL_INTERVAL
        );
    }

    PollingStatusWatcher(
            Path statusFile,
            StatusSnapshotParser parser,
            StatusObservationAdapter adapter,
            ObservationBus bus,
            Clock clock,
            Duration pollInterval
    ) {
        this.statusFile = Objects.requireNonNull(statusFile, "statusFile")
                .toAbsolutePath()
                .normalize();
        this.parser = Objects.requireNonNull(parser, "parser");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        String actualBasename = this.statusFile.getFileName() == null
                ? ""
                : this.statusFile.getFileName().toString();
        if (!adapter.statusBasename().equals(actualBasename)) {
            throw new IllegalArgumentException(
                    "adapter status basename does not match statusFile"
            );
        }
        sourceExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "status-source");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Publishes the current valid snapshot as {@code BOOTSTRAP}. Absence or a
     * transient malformed replacement is a successful empty baseline; the
     * first later valid LIVE snapshot will be published and can become the
     * subscriber's baseline.
     */
    public CompletionStage<BootstrapPublicationReport> publishBootstrap() {
        synchronized (this) {
            if (lifecycle != Lifecycle.NEW) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "status bootstrap may run exactly once"
                        )
                );
            }
            lifecycle = Lifecycle.BOOTSTRAPPING;
        }
        try {
            return CompletableFuture.supplyAsync(
                    this::publishBootstrapOnSourceThread,
                    sourceExecutor
            );
        } catch (RejectedExecutionException rejection) {
            failSource(rejection);
            return CompletableFuture.failedFuture(rejection);
        }
    }

    public synchronized void startWatching() {
        if (lifecycle != Lifecycle.READY) {
            throw new IllegalStateException(
                    "status watching requires successful bootstrap"
            );
        }
        lifecycle = Lifecycle.WATCHING;
        try {
            pollingTask = sourceExecutor.scheduleWithFixedDelay(
                    this::runScheduledPoll,
                    0,
                    pollInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException rejection) {
            failSource(rejection);
            throw rejection;
        }
    }

    /**
     * Test seam and explicit single-cycle source operation.
     */
    CompletionStage<Void> pollNow() {
        synchronized (this) {
            if (lifecycle != Lifecycle.READY
                    && lifecycle != Lifecycle.WATCHING) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "status source is not ready for polling"
                        )
                );
            }
            try {
                return CompletableFuture.runAsync(
                        this::runManualPoll,
                        sourceExecutor
                );
            } catch (RejectedExecutionException rejection) {
                failSource(rejection);
                return CompletableFuture.failedFuture(rejection);
            }
        }
    }

    /**
     * Stops new polls and serializes one final whole-file read behind a poll
     * already in progress.
     */
    public CompletionStage<StatusStopReport> stopAndDrain() {
        synchronized (this) {
            if (stopStage != null) {
                return stopStage;
            }
            if (pollingTask != null) {
                pollingTask.cancel(false);
            }
            Lifecycle previous = lifecycle;
            lifecycle = Lifecycle.STOPPING;
            try {
                stopStage = CompletableFuture.supplyAsync(
                        () -> stopAndDrainOnSourceThread(previous),
                        sourceExecutor
                );
            } catch (RejectedExecutionException rejection) {
                failSource(rejection);
                stopStage = CompletableFuture.completedFuture(stopReport());
            }
            return stopStage;
        }
    }

    public CompletionStage<Throwable> terminalFailure() {
        return terminalFailureStage.minimalCompletionStage();
    }

    @Override
    public void close() {
        try {
            stopAndDrain().toCompletableFuture().join();
        } finally {
            sourceExecutor.shutdown();
        }
    }

    private BootstrapPublicationReport publishBootstrapOnSourceThread() {
        int publishedCount = 0;
        try {
            if (publishChangedSnapshot(ObservationCaptureMode.BOOTSTRAP)) {
                publishedCount = 1;
            }
            lifecycle = Lifecycle.READY;
            return bootstrapReport(publishedCount, true);
        } catch (RuntimeException failure) {
            failSource(unwrap(failure));
            BootstrapPublicationReport report =
                    bootstrapReport(publishedCount, false);
            throw new BootstrapPublicationException(
                    report,
                    terminalFailure
            );
        }
    }

    private void runScheduledPoll() {
        if (lifecycle != Lifecycle.WATCHING) {
            return;
        }
        try {
            publishChangedSnapshot(ObservationCaptureMode.LIVE);
        } catch (RuntimeException failure) {
            failSource(unwrap(failure));
        }
    }

    private void runManualPoll() {
        try {
            publishChangedSnapshot(ObservationCaptureMode.LIVE);
        } catch (RuntimeException failure) {
            failSource(unwrap(failure));
            throw failure;
        }
    }

    private StatusStopReport stopAndDrainOnSourceThread(
            Lifecycle previousLifecycle
    ) {
        if (terminalFailure == null
                && (previousLifecycle == Lifecycle.READY
                || previousLifecycle == Lifecycle.WATCHING)) {
            try {
                publishChangedSnapshot(ObservationCaptureMode.LIVE);
            } catch (RuntimeException failure) {
                failSource(unwrap(failure));
            }
        }
        lifecycle = terminalFailure == null
                ? Lifecycle.STOPPED
                : Lifecycle.FAILED;
        return stopReport();
    }

    private boolean publishChangedSnapshot(
            ObservationCaptureMode captureMode
    ) {
        Optional<byte[]> optionalBytes = readCurrentContent();
        if (optionalBytes.isEmpty()) {
            return false;
        }
        byte[] bytes = optionalBytes.orElseThrow();
        if (committedContent != null
                && Arrays.equals(committedContent, bytes)) {
            return false;
        }

        StatusParseResult result = parser.parse(bytes);
        if (result instanceof StatusParseFailure failure) {
            diagnoseParseFailure(failure);
            return false;
        }
        StatusSnapshotObservation snapshot =
                ((ParsedStatusSnapshot) result).observation();
        ObservationDraft<StatusSnapshotObservation> draft = adapter.adapt(
                snapshot,
                nextSnapshotSequence,
                captureMode,
                clock.instant()
        );
        PublishReceipt receipt;
        try {
            receipt = bus.publish(draft).toCompletableFuture().join();
        } catch (RuntimeException failure) {
            throw new StatusPublicationException(
                    draft.observationId(),
                    unwrap(failure)
            );
        }

        committedContent = Arrays.copyOf(bytes, bytes.length);
        nextSnapshotSequence = Math.incrementExact(nextSnapshotSequence);
        acceptedHighWaterBusSequence = Math.max(
                acceptedHighWaterBusSequence,
                receipt.busSequence()
        );
        for (String subscriberId : receipt.failedSubscriberIds()) {
            handlerFailures.add(new SubscriberHandlerFailure(
                    subscriberId,
                    receipt.observationId(),
                    receipt.busSequence()
            ));
        }
        return true;
    }

    private Optional<byte[]> readCurrentContent() {
        try {
            if (!Files.isRegularFile(statusFile)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(statusFile));
        } catch (NoSuchFileException missingDuringReplacement) {
            return Optional.empty();
        } catch (IOException transientFailure) {
            diagnoseTransientRead(transientFailure);
            return Optional.empty();
        }
    }

    private void diagnoseParseFailure(StatusParseFailure failure) {
        Instant now = clock.instant();
        if (now.isBefore(nextTransientDiagnosticAt)) {
            return;
        }
        LOGGER.warn(
                "STATUS_SNAPSHOT_REJECTED basename={} kind={} diagnostic={}",
                adapter.statusBasename(),
                failure.kind(),
                failure.diagnostic()
        );
        nextTransientDiagnosticAt =
                now.plus(TRANSIENT_DIAGNOSTIC_INTERVAL);
    }

    private void diagnoseTransientRead(IOException failure) {
        Instant now = clock.instant();
        if (now.isBefore(nextTransientDiagnosticAt)) {
            return;
        }
        LOGGER.warn(
                "STATUS_TRANSIENT_READ_FAILED path={} category={}",
                statusFile,
                failure.getClass().getSimpleName()
        );
        nextTransientDiagnosticAt =
                now.plus(TRANSIENT_DIAGNOSTIC_INTERVAL);
    }

    private void failSource(Throwable failure) {
        Throwable effective = Objects.requireNonNull(failure, "failure");
        if (terminalFailure != null) {
            return;
        }
        terminalFailure = effective;
        lifecycle = Lifecycle.FAILED;
        if (pollingTask != null) {
            pollingTask.cancel(false);
        }
        terminalFailureStage.complete(effective);
        LOGGER.error(
                "STATUS_SOURCE_FAILED basename={} category={}",
                adapter.statusBasename(),
                effective.getClass().getSimpleName()
        );
    }

    private BootstrapPublicationReport bootstrapReport(
            int publishedCount,
            boolean successful
    ) {
        return new BootstrapPublicationReport(
                publishedCount,
                optionalHighWater(),
                List.copyOf(handlerFailures),
                successful
        );
    }

    private StatusStopReport stopReport() {
        return new StatusStopReport(
                optionalHighWater(),
                List.copyOf(handlerFailures),
                Optional.ofNullable(terminalFailure)
        );
    }

    private OptionalLong optionalHighWater() {
        return acceptedHighWaterBusSequence == 0
                ? OptionalLong.empty()
                : OptionalLong.of(acceptedHighWaterBusSequence);
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public record BootstrapPublicationReport(
            int publishedSnapshotCount,
            OptionalLong acceptedHighWaterBusSequence,
            List<SubscriberHandlerFailure> handlerFailures,
            boolean successful
    ) {

        public BootstrapPublicationReport {
            if (publishedSnapshotCount < 0) {
                throw new IllegalArgumentException(
                        "publishedSnapshotCount must be nonnegative"
                );
            }
            acceptedHighWaterBusSequence = Objects.requireNonNull(
                    acceptedHighWaterBusSequence,
                    "acceptedHighWaterBusSequence"
            );
            handlerFailures = List.copyOf(
                    Objects.requireNonNull(
                            handlerFailures,
                            "handlerFailures"
                    )
            );
        }

        public boolean handlerFailed(String subscriberId) {
            Objects.requireNonNull(subscriberId, "subscriberId");
            return handlerFailures.stream().anyMatch(failure ->
                    failure.subscriberId().equals(subscriberId));
        }
    }

    public record StatusStopReport(
            OptionalLong acceptedHighWaterBusSequence,
            List<SubscriberHandlerFailure> handlerFailures,
            Optional<Throwable> failure
    ) {

        public StatusStopReport {
            acceptedHighWaterBusSequence = Objects.requireNonNull(
                    acceptedHighWaterBusSequence,
                    "acceptedHighWaterBusSequence"
            );
            handlerFailures = List.copyOf(
                    Objects.requireNonNull(
                            handlerFailures,
                            "handlerFailures"
                    )
            );
            failure = Objects.requireNonNull(failure, "failure");
        }

        public boolean successful() {
            return failure.isEmpty();
        }

        public boolean handlerFailed(String subscriberId) {
            Objects.requireNonNull(subscriberId, "subscriberId");
            return handlerFailures.stream().anyMatch(handlerFailure ->
                    handlerFailure.subscriberId().equals(subscriberId));
        }
    }

    public record SubscriberHandlerFailure(
            String subscriberId,
            String observationId,
            long busSequence
    ) {

        public SubscriberHandlerFailure {
            subscriberId = requireNonBlank(
                    subscriberId,
                    "subscriberId"
            );
            observationId = requireNonBlank(
                    observationId,
                    "observationId"
            );
            if (busSequence < 1) {
                throw new IllegalArgumentException(
                        "busSequence must be positive"
                );
            }
        }
    }

    public static final class BootstrapPublicationException
            extends IllegalStateException {

        private final BootstrapPublicationReport report;

        private BootstrapPublicationException(
                BootstrapPublicationReport report,
                Throwable cause
        ) {
            super("status bootstrap publication failed", cause);
            this.report = Objects.requireNonNull(report, "report");
        }

        public BootstrapPublicationReport report() {
            return report;
        }
    }

    public static final class StatusPublicationException
            extends IllegalStateException {

        private final String observationId;

        private StatusPublicationException(
                String observationId,
                Throwable cause
        ) {
            super("Status snapshot publication failed", cause);
            this.observationId = requireNonBlank(
                    observationId,
                    "observationId"
            );
        }

        public String observationId() {
            return observationId;
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private enum Lifecycle {
        NEW,
        BOOTSTRAPPING,
        READY,
        WATCHING,
        STOPPING,
        STOPPED,
        FAILED
    }
}
