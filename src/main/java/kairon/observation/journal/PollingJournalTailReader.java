package kairon.observation.journal;

import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.bus.ObservationBus;
import kairon.observation.bus.ObservationBus.PublishReceipt;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.JournalParseFailure;
import kairon.observation.journal.JournalLineParser.JournalParseResult;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter.ExactDuplicateJournalObservationException;
import kairon.observation.journal.JournalObservationAdapter.JournalSourcePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls one ordered Elite Dangerous journal stream and publishes complete
 * records through {@link ObservationBus}.
 *
 * <p>The source thread deliberately waits for each publish receipt before
 * reading the next complete record. This keeps source order explicit while the
 * bus and its handoff-only subscribers remain independent of file I/O.</p>
 */
public final class PollingJournalTailReader implements AutoCloseable {

    public static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    public static final Duration ROTATION_PARTIAL_LINE_TIMEOUT = Duration.ofMillis(2000);
    public static final int BOOTSTRAP_RECORD_LIMIT = 30;
    private static final int CURSOR_GUARD_BYTES = 256;
    private static final Duration TRANSIENT_READ_DIAGNOSTIC_INTERVAL =
            Duration.ofSeconds(5);

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PollingJournalTailReader.class);
    private final Path journalDirectory;
    private final JournalLineParser parser;
    private final JournalObservationAdapter adapter;
    private final ObservationBus bus;
    private final Clock clock;
    private final Duration pollInterval;
    private final Duration rotationPartialLineTimeout;
    private final ScheduledExecutorService sourceExecutor;

    private final List<SubscriberHandlerFailure> handlerFailures = new ArrayList<>();
    private final List<JournalSourcePosition> uncommittedPositions = new ArrayList<>();

    private volatile Lifecycle lifecycle = Lifecycle.NEW;
    private volatile boolean stopRequested;
    private ScheduledFuture<?> pollingTask;
    private Path activeFile;
    private String activeBasename;
    private Object activeFileKey;
    private FileTime activeCreationTime;
    private byte[] activeCursorGuard = new byte[0];
    private long activeCursorGuardOffset;
    private String retiredBasename;
    private long nextReadOffset;
    private long committedOffset;
    private byte[] partialRecord = new byte[0];
    private long partialRecordOffset;
    private Path rotationSuccessor;
    private Instant rotationDeadline;
    private long acceptedHighWaterBusSequence;
    private Throwable terminalFailure;
    private CompletableFuture<JournalStopReport> stopStage;
    private final CompletableFuture<Throwable> terminalFailureStage =
            new CompletableFuture<>();
    private Instant nextTransientReadDiagnosticAt = Instant.MIN;

    public PollingJournalTailReader(
            Path journalDirectory,
            JournalLineParser parser,
            JournalObservationAdapter adapter,
            ObservationBus bus
    ) {
        this(
                journalDirectory,
                parser,
                adapter,
                bus,
                Clock.systemUTC(),
                POLL_INTERVAL,
                ROTATION_PARTIAL_LINE_TIMEOUT
        );
    }

    public PollingJournalTailReader(
            Path journalDirectory,
            JournalLineParser parser,
            JournalObservationAdapter adapter,
            ObservationBus bus,
            Clock clock
    ) {
        this(
                journalDirectory,
                parser,
                adapter,
                bus,
                clock,
                POLL_INTERVAL,
                ROTATION_PARTIAL_LINE_TIMEOUT
        );
    }

    PollingJournalTailReader(
            Path journalDirectory,
            JournalLineParser parser,
            JournalObservationAdapter adapter,
            ObservationBus bus,
            Clock clock,
            Duration pollInterval,
            Duration rotationPartialLineTimeout
    ) {
        this.journalDirectory =
                Objects.requireNonNull(journalDirectory, "journalDirectory")
                        .toAbsolutePath()
                        .normalize();
        this.parser = Objects.requireNonNull(parser, "parser");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        this.rotationPartialLineTimeout =
                requirePositive(rotationPartialLineTimeout, "rotationPartialLineTimeout");
        this.sourceExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "journal-source");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Captures one startup boundary and publishes only the last up to thirty
     * valid, complete records below it as {@code BOOTSTRAP}.
     */
    public CompletionStage<BootstrapPublicationReport> publishBootstrap() {
        synchronized (this) {
            if (lifecycle != Lifecycle.NEW) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("bootstrap may run exactly once")
                );
            }
            lifecycle = Lifecycle.BOOTSTRAPPING;
        }

        return CompletableFuture.supplyAsync(this::publishBootstrapOnSourceThread, sourceExecutor);
    }

    /**
     * Starts fixed-delay live polling after successful bootstrap publication.
     */
    public synchronized void startFollowing() {
        if (lifecycle != Lifecycle.READY || stopRequested) {
            throw new IllegalStateException(
                    "live following requires successful bootstrap publication"
            );
        }
        lifecycle = Lifecycle.FOLLOWING;
        pollingTask = sourceExecutor.scheduleWithFixedDelay(
                this::runScheduledPoll,
                0,
                pollInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stops new polling and serializes one final complete-record drain behind
     * any poll already running.
     */
    public CompletionStage<JournalStopReport> stopAndDrain() {
        synchronized (this) {
            if (stopStage != null) {
                return stopStage;
            }
            if (pollingTask != null) {
                pollingTask.cancel(false);
            }
            stopRequested = true;
            Lifecycle previous = lifecycle;
            lifecycle = Lifecycle.STOPPING;
            stopStage = CompletableFuture.supplyAsync(
                    () -> stopAndDrainOnSourceThread(previous),
                    sourceExecutor
            );
            return stopStage;
        }
    }

    /**
     * Completes only when live polling stops because of a terminal source
     * failure. This is an application-lifecycle signal, not an observation or
     * a subscriber callback.
     */
    public CompletionStage<Throwable> terminalFailure() {
        return terminalFailureStage.minimalCompletionStage();
    }

    /**
     * Test seam for deterministic polling without sleeping. It uses the exact
     * production poll cycle and remains package-private.
     */
    CompletionStage<Void> pollNow() {
        synchronized (this) {
            if (lifecycle != Lifecycle.READY && lifecycle != Lifecycle.FOLLOWING) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("source is not ready for polling")
                );
            }
            return CompletableFuture.runAsync(this::runManualPoll, sourceExecutor);
        }
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
        int selectedCount = 0;
        int publishedCount = 0;

        try {
            Optional<Path> selected = greatestJournalFile();
            if (selected.isEmpty()) {
                markBootstrapComplete();
                return bootstrapReport(0, 0, true);
            }

            activateAtStartup(selected.orElseThrow());
            long startupBoundaryOffset = Files.size(activeFile);
            BootstrapScan bootstrapScan = scanBootstrapBoundary(startupBoundaryOffset);
            selectedCount = bootstrapScan.selectedRecords().size();

            for (ParsedJournalRecord record : bootstrapScan.selectedRecords()) {
                try {
                    publishRecord(record, ObservationCaptureMode.BOOTSTRAP);
                    publishedCount++;
                } catch (ExactDuplicateJournalObservationException duplicate) {
                    diagnoseExactDuplicate(
                            record.journalBasename(),
                            record.zeroBasedSourceByteOffset()
                    );
                }
            }

            committedOffset = startupBoundaryOffset;
            markBootstrapComplete();
            return bootstrapReport(selectedCount, publishedCount, true);
        } catch (Exception exception) {
            terminalFailure = unwrap(exception);
            terminalFailureStage.complete(terminalFailure);
            lifecycle = Lifecycle.FAILED;
            BootstrapPublicationReport report =
                    bootstrapReport(selectedCount, publishedCount, false);
            throw new BootstrapPublicationException(report, terminalFailure);
        }
    }

    private void runScheduledPoll() {
        try {
            if (lifecycle == Lifecycle.FOLLOWING) {
                pollCycle();
                nextTransientReadDiagnosticAt = Instant.MIN;
            }
        } catch (RuntimeException exception) {
            failSource(exception);
        } catch (IOException exception) {
            Instant now = clock.instant();
            if (!now.isBefore(nextTransientReadDiagnosticAt)) {
                LOGGER.warn(
                        "JOURNAL_TRANSIENT_READ_FAILED directory={} activeBasename={}",
                        journalDirectory,
                        activeBasename,
                        exception
                );
                nextTransientReadDiagnosticAt =
                        now.plus(TRANSIENT_READ_DIAGNOSTIC_INTERVAL);
            }
        }
    }

    private void runManualPoll() {
        try {
            pollCycle();
        } catch (RuntimeException exception) {
            failSource(exception);
            throw exception;
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private void pollCycle() throws IOException {
        if (activeFile == null) {
            Optional<Path> next = nextJournalFile();
            if (next.isEmpty()) {
                return;
            }
            activateForLive(next.orElseThrow());
        }

        try {
            readActiveSnapshot(ObservationCaptureMode.LIVE);
        } catch (NoSuchFileException exception) {
            retireAnomalousActiveFile("JOURNAL_ACTIVE_FILE_MISSING");
            return;
        }
        if (activeFile == null) {
            return;
        }

        Optional<Path> successor = leastGreaterJournalFile(activeBasename);
        if (successor.isEmpty()) {
            maintainKnownRotationDeadlineWithoutVisibleSuccessor();
            return;
        }

        if (rotationSuccessor == null) {
            rotationSuccessor = successor.orElseThrow();
            rotationDeadline = partialRecord.length == 0
                    ? null
                    : clock.instant().plus(rotationPartialLineTimeout);
        } else if (!rotationSuccessor.equals(successor.orElseThrow())) {
            // A successor was already known, so changing the candidate must
            // not extend an in-progress predecessor-tail deadline.
            rotationSuccessor = successor.orElseThrow();
        }

        // One final size snapshot after the successor is known.
        readActiveSnapshot(ObservationCaptureMode.LIVE);
        if (activeFile == null) {
            return;
        }
        if (partialRecord.length == 0) {
            switchToSuccessor();
            readActiveSnapshot(ObservationCaptureMode.LIVE);
            return;
        }

        if (rotationDeadline == null) {
            rotationDeadline = clock.instant().plus(rotationPartialLineTimeout);
        }
        if (!clock.instant().isBefore(rotationDeadline)) {
            abandonIncompleteRotationTail();
            switchToSuccessor();
            readActiveSnapshot(ObservationCaptureMode.LIVE);
        }
    }

    private JournalStopReport stopAndDrainOnSourceThread(Lifecycle previousLifecycle) {
        Throwable drainFailure = terminalFailure;
        if (previousLifecycle != Lifecycle.FAILED
                && terminalFailure == null
                && activeFile != null) {
            try {
                readActiveSnapshot(ObservationCaptureMode.LIVE);
                if (partialRecord.length > 0) {
                    LOGGER.warn(
                            "SHUTDOWN_INCOMPLETE_TAIL_ABANDONED basename={} "
                                    + "zeroBasedSourceByteOffset={} byteCount={}",
                            activeBasename,
                            partialRecordOffset,
                            partialRecord.length
                    );
                    partialRecord = new byte[0];
                }
            } catch (Exception exception) {
                drainFailure = unwrap(exception);
                terminalFailure = drainFailure;
                LOGGER.error(
                        "FINAL_DRAIN_PUBLICATION_FAILED basename={} committedOffset={}",
                        activeBasename,
                        committedOffset,
                        drainFailure
                );
            }
        }
        lifecycle = Lifecycle.STOPPED;
        return new JournalStopReport(
                optionalHighWater(),
                List.copyOf(uncommittedPositions),
                List.copyOf(handlerFailures),
                Optional.ofNullable(drainFailure)
        );
    }

    private void activateAtStartup(Path file) throws IOException {
        activeFile = file;
        activeBasename = basename(file);
        captureActiveFileIdentity(file);
        retiredBasename = null;
        nextReadOffset = 0;
        committedOffset = 0;
        partialRecord = new byte[0];
        partialRecordOffset = 0;
        activeCursorGuard = new byte[0];
        activeCursorGuardOffset = 0;
    }

    private void activateForLive(Path file) throws IOException {
        activeFile = file;
        activeBasename = basename(file);
        captureActiveFileIdentity(file);
        nextReadOffset = 0;
        committedOffset = 0;
        partialRecord = new byte[0];
        partialRecordOffset = 0;
        activeCursorGuard = new byte[0];
        activeCursorGuardOffset = 0;
        rotationSuccessor = null;
        rotationDeadline = null;
    }

    private void switchToSuccessor() throws IOException {
        Path successor = Objects.requireNonNull(rotationSuccessor, "rotationSuccessor");
        retiredBasename = activeBasename;
        activateForLive(successor);
    }

    private void retireAnomalousActiveFile(String diagnosticCode) {
        LOGGER.error(
                "{} basename={} nextReadOffset={} committedOffset={} "
                        + "partialRecordOffset={} partialByteCount={}",
                diagnosticCode,
                activeBasename,
                nextReadOffset,
                committedOffset,
                partialRecordOffset,
                partialRecord.length
        );
        retiredBasename = activeBasename;
        activeFile = null;
        activeBasename = null;
        activeFileKey = null;
        activeCreationTime = null;
        activeCursorGuard = new byte[0];
        activeCursorGuardOffset = 0;
        partialRecord = new byte[0];
        rotationSuccessor = null;
        rotationDeadline = null;
    }

    private synchronized void markBootstrapComplete() {
        if (!stopRequested) {
            lifecycle = Lifecycle.READY;
        }
    }

    private void maintainKnownRotationDeadlineWithoutVisibleSuccessor() {
        if (rotationSuccessor == null || partialRecord.length == 0) {
            return;
        }
        if (rotationDeadline == null) {
            rotationDeadline = clock.instant().plus(rotationPartialLineTimeout);
        }
        if (!clock.instant().isBefore(rotationDeadline)) {
            abandonIncompleteRotationTail();
        }
    }

    private void abandonIncompleteRotationTail() {
        LOGGER.warn(
                "ROTATION_INCOMPLETE_TAIL_ABANDONED basename={} "
                        + "zeroBasedSourceByteOffset={} byteCount={}",
                activeBasename,
                partialRecordOffset,
                partialRecord.length
        );
        partialRecord = new byte[0];
        partialRecordOffset = nextReadOffset;
    }

    private BootstrapScan scanBootstrapBoundary(long startupBoundaryOffset)
            throws IOException {
        byte[] bytes = readRange(activeFile, 0, startupBoundaryOffset);
        ArrayDeque<ParsedJournalRecord> suffix = new ArrayDeque<>(BOOTSTRAP_RECORD_LIMIT);
        int recordStart = 0;

        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] != '\n') {
                continue;
            }
            int contentEnd = index;
            if (contentEnd > recordStart && bytes[contentEnd - 1] == '\r') {
                contentEnd--;
            }
            JournalParseResult result = parser.parse(new CompleteJournalRecord(
                    activeBasename,
                    recordStart,
                    Arrays.copyOfRange(bytes, recordStart, contentEnd)
            ));
            if (result instanceof ParsedJournalRecord parsed) {
                if (suffix.size() == BOOTSTRAP_RECORD_LIMIT) {
                    suffix.removeFirst();
                }
                suffix.addLast(parsed);
            } else {
                diagnoseParseFailure((JournalParseFailure) result);
            }
            recordStart = index + 1;
        }

        partialRecord = Arrays.copyOfRange(bytes, recordStart, bytes.length);
        partialRecordOffset = recordStart;
        nextReadOffset = startupBoundaryOffset;
        refreshCursorGuard();
        return new BootstrapScan(List.copyOf(suffix));
    }

    private void readActiveSnapshot(ObservationCaptureMode captureMode) throws IOException {
        BasicFileAttributes attributes =
                Files.readAttributes(activeFile, BasicFileAttributes.class);
        Object currentFileKey = attributes.fileKey();
        boolean fileKeyChanged = activeFileKey != null
                && currentFileKey != null
                && !activeFileKey.equals(currentFileKey);
        boolean creationTimeChanged = activeCreationTime != null
                && !activeCreationTime.equals(attributes.creationTime());
        if (fileKeyChanged || creationTimeChanged) {
            retireAnomalousActiveFile("JOURNAL_ACTIVE_FILE_TRUNCATED_OR_REPLACED");
            return;
        }
        long snapshotSize = attributes.size();
        if (snapshotSize < nextReadOffset) {
            retireAnomalousActiveFile("JOURNAL_ACTIVE_FILE_TRUNCATED_OR_REPLACED");
            return;
        }
        if (!cursorGuardMatches()) {
            retireAnomalousActiveFile("JOURNAL_ACTIVE_FILE_TRUNCATED_OR_REPLACED");
            return;
        }
        if (snapshotSize == nextReadOffset) {
            return;
        }

        long newBytesStart = nextReadOffset;
        byte[] newBytes = readRange(activeFile, newBytesStart, snapshotSize);
        byte[] combined = concatenate(partialRecord, newBytes);
        long combinedStartOffset =
                partialRecord.length == 0 ? newBytesStart : partialRecordOffset;
        int recordStart = 0;

        for (int index = 0; index < combined.length; index++) {
            if (combined[index] != '\n') {
                continue;
            }
            int contentEnd = index;
            if (contentEnd > recordStart && combined[contentEnd - 1] == '\r') {
                contentEnd--;
            }
            long sourceOffset = combinedStartOffset + recordStart;
            JournalParseResult result = parser.parse(new CompleteJournalRecord(
                    activeBasename,
                    sourceOffset,
                    Arrays.copyOfRange(combined, recordStart, contentEnd)
            ));
            if (result instanceof ParsedJournalRecord parsed) {
                try {
                    publishRecord(parsed, captureMode);
                } catch (ExactDuplicateJournalObservationException duplicate) {
                    diagnoseExactDuplicate(activeBasename, sourceOffset);
                }
            } else {
                diagnoseParseFailure((JournalParseFailure) result);
            }
            committedOffset = combinedStartOffset + index + 1;
            recordStart = index + 1;
        }

        partialRecord = Arrays.copyOfRange(combined, recordStart, combined.length);
        partialRecordOffset = combinedStartOffset + recordStart;
        nextReadOffset = snapshotSize;
        refreshCursorGuard();
    }

    private void publishRecord(
            ParsedJournalRecord record,
            ObservationCaptureMode captureMode
    ) {
        var draft = adapter.adapt(record, captureMode, clock.instant());
        PublishReceipt receipt;
        try {
            receipt = bus.publish(draft).toCompletableFuture().join();
        } catch (RuntimeException exception) {
            adapter.rollback(draft.observationId());
            uncommittedPositions.add(
                    new JournalSourcePosition(
                            record.journalBasename(),
                            record.zeroBasedSourceByteOffset()
                    )
            );
            throw new SourcePublicationException(draft.observationId(), unwrap(exception));
        }

        adapter.commit(draft.observationId());
        acceptedHighWaterBusSequence =
                Math.max(acceptedHighWaterBusSequence, receipt.busSequence());
        for (String subscriberId : receipt.failedSubscriberIds()) {
            handlerFailures.add(new SubscriberHandlerFailure(
                    subscriberId,
                    receipt.observationId(),
                    receipt.busSequence()
            ));
        }
    }

    private Optional<Path> greatestJournalFile() throws IOException {
        return journalFiles().stream().max(Comparator.comparing(PollingJournalTailReader::basename));
    }

    private Optional<Path> nextJournalFile() throws IOException {
        List<Path> files = journalFiles();
        if (retiredBasename == null) {
            return files.stream()
                    .min(Comparator.comparing(PollingJournalTailReader::basename));
        }
        return files.stream()
                .filter(path -> basename(path).compareTo(retiredBasename) > 0)
                .min(Comparator.comparing(PollingJournalTailReader::basename));
    }

    private Optional<Path> leastGreaterJournalFile(String basename) throws IOException {
        return journalFiles().stream()
                .filter(path -> PollingJournalTailReader.basename(path).compareTo(basename) > 0)
                .min(Comparator.comparing(PollingJournalTailReader::basename));
    }

    private List<Path> journalFiles() throws IOException {
        if (!Files.isDirectory(journalDirectory)) {
            return List.of();
        }
        try (var paths = Files.list(journalDirectory)) {
            return paths
                    .filter(path -> Files.isRegularFile(path))
                    .filter(path -> isJournalBasename(basename(path)))
                    .sorted(Comparator.comparing(PollingJournalTailReader::basename))
                    .toList();
        }
    }

    private static boolean isJournalBasename(String basename) {
        return basename.startsWith("Journal.")
                && basename.endsWith(".log");
    }

    private void captureActiveFileIdentity(Path file) throws IOException {
        BasicFileAttributes attributes =
                Files.readAttributes(file, BasicFileAttributes.class);
        activeFileKey = attributes.fileKey();
        activeCreationTime = attributes.creationTime();
    }

    private boolean cursorGuardMatches() throws IOException {
        if (activeCursorGuard.length == 0) {
            return true;
        }
        byte[] current = readRange(
                activeFile,
                activeCursorGuardOffset,
                activeCursorGuardOffset + activeCursorGuard.length
        );
        return Arrays.equals(activeCursorGuard, current);
    }

    private void refreshCursorGuard() throws IOException {
        int guardLength = (int) Math.min(CURSOR_GUARD_BYTES, nextReadOffset);
        activeCursorGuardOffset = nextReadOffset - guardLength;
        activeCursorGuard = guardLength == 0
                ? new byte[0]
                : readRange(activeFile, activeCursorGuardOffset, nextReadOffset);
    }

    private static String basename(Path path) {
        return path.getFileName().toString();
    }

    private static byte[] readRange(Path file, long startInclusive, long endExclusive)
            throws IOException {
        if (endExclusive < startInclusive) {
            throw new IllegalArgumentException("endExclusive precedes startInclusive");
        }
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
                    throw new EOFException("journal shrank during a bounded read");
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

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private void diagnoseParseFailure(JournalParseFailure failure) {
        LOGGER.warn(
                "JOURNAL_RECORD_REJECTED basename={} zeroBasedSourceByteOffset={} kind={} "
                        + "diagnostic={}",
                failure.journalBasename(),
                failure.zeroBasedSourceByteOffset(),
                failure.kind(),
                failure.diagnostic()
        );
    }

    private static void diagnoseExactDuplicate(String basename, long sourceOffset) {
        LOGGER.warn(
                "EXACT_SOURCE_DUPLICATE observationId={} basename={} offset={}",
                JournalObservationAdapter.journalObservationId(basename, sourceOffset),
                basename,
                sourceOffset
        );
    }

    private void failSource(Throwable failure) {
        terminalFailure = unwrap(failure);
        lifecycle = Lifecycle.FAILED;
        if (pollingTask != null) {
            pollingTask.cancel(false);
        }
        LOGGER.error(
                "JOURNAL_SOURCE_FAILED basename={} committedOffset={}",
                activeBasename,
                committedOffset,
                terminalFailure
        );
        terminalFailureStage.complete(terminalFailure);
    }

    private BootstrapPublicationReport bootstrapReport(
            int selectedCount,
            int publishedCount,
            boolean successful
    ) {
        return new BootstrapPublicationReport(
                selectedCount,
                publishedCount,
                optionalHighWater(),
                List.copyOf(handlerFailures),
                successful
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

    private static Throwable unwrap(Throwable exception) {
        Throwable current = exception;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Source-to-bus transport report. {@code successful} means every attempted
     * non-duplicate publication received a normal receipt; handler failures
     * remain separately visible and do not turn a transport receipt into
     * source-data failure.
     */
    public record BootstrapPublicationReport(
            int selectedRecordCount,
            int publishedRecordCount,
            OptionalLong acceptedHighWaterBusSequence,
            List<SubscriberHandlerFailure> handlerFailures,
            boolean successful
    ) {

        public BootstrapPublicationReport {
            acceptedHighWaterBusSequence =
                    Objects.requireNonNull(
                            acceptedHighWaterBusSequence,
                            "acceptedHighWaterBusSequence"
                    );
            handlerFailures = List.copyOf(
                    Objects.requireNonNull(handlerFailures, "handlerFailures")
            );
        }

        public boolean handlerFailed(String subscriberId) {
            Objects.requireNonNull(subscriberId, "subscriberId");
            return handlerFailures.stream()
                    .anyMatch(failure -> failure.subscriberId().equals(subscriberId));
        }
    }

    /**
     * Final source-drain report. Incomplete non-record tails are diagnosed and
     * abandoned by policy, so only failed complete-record publications appear
     * in {@code uncommittedPositions}.
     */
    public record JournalStopReport(
            OptionalLong acceptedHighWaterBusSequence,
            List<JournalSourcePosition> uncommittedPositions,
            List<SubscriberHandlerFailure> handlerFailures,
            Optional<Throwable> failure
    ) {

        public JournalStopReport {
            acceptedHighWaterBusSequence =
                    Objects.requireNonNull(
                            acceptedHighWaterBusSequence,
                            "acceptedHighWaterBusSequence"
                    );
            uncommittedPositions = List.copyOf(
                    Objects.requireNonNull(uncommittedPositions, "uncommittedPositions")
            );
            handlerFailures = List.copyOf(
                    Objects.requireNonNull(handlerFailures, "handlerFailures")
            );
            failure = Objects.requireNonNull(failure, "failure");
        }

        public boolean successful() {
            return failure.isEmpty() && uncommittedPositions.isEmpty();
        }

        public boolean handlerFailed(String subscriberId) {
            Objects.requireNonNull(subscriberId, "subscriberId");
            return handlerFailures.stream()
                    .anyMatch(handlerFailure ->
                            handlerFailure.subscriberId().equals(subscriberId));
        }
    }

    public record SubscriberHandlerFailure(
            String subscriberId,
            String observationId,
            long busSequence
    ) {

        public SubscriberHandlerFailure {
            subscriberId = Objects.requireNonNull(subscriberId, "subscriberId");
            observationId = Objects.requireNonNull(observationId, "observationId");
        }
    }

    public static final class BootstrapPublicationException extends RuntimeException {

        private final BootstrapPublicationReport report;

        public BootstrapPublicationException(
                BootstrapPublicationReport report,
                Throwable cause
        ) {
            super("bootstrap publication failed", cause);
            this.report = Objects.requireNonNull(report, "report");
        }

        public BootstrapPublicationReport report() {
            return report;
        }
    }

    private record BootstrapScan(List<ParsedJournalRecord> selectedRecords) {
    }

    private enum Lifecycle {
        NEW,
        BOOTSTRAPPING,
        READY,
        FOLLOWING,
        STOPPING,
        STOPPED,
        FAILED
    }

    private static final class SourcePublicationException extends RuntimeException {

        private SourcePublicationException(String observationId, Throwable cause) {
            super("observation publication failed: " + observationId, cause);
        }
    }
}
