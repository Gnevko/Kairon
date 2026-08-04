package kairon.observation.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.PublishedObservation;
import kairon.observation.bus.InProcessObservationBus;
import kairon.observation.bus.ObservationBus.ObservationSubscription;
import kairon.observation.status.PollingStatusWatcher.BootstrapPublicationReport;
import kairon.observation.status.PollingStatusWatcher.StatusStopReport;
import kairon.observation.status.StatusObservationAdapter.StatusSourcePosition;
import kairon.observation.status.StatusSnapshotParser.ParsedStatusSnapshot;
import kairon.observation.status.StatusSnapshotParser.StatusParseFailure;
import kairon.observation.status.StatusSnapshotParser.StatusParseFailureKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusSourceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-29T12:00:00Z"),
            ZoneOffset.UTC
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void parserPreservesExactSourceUnknownFieldsAndImmutableJson() {
        String raw = " {\"timestamp\":\"2026-07-29T11:59:58Z\","
                + "\"event\":\"Status\",\"Flags\":4294967295,"
                + "\"Flags2\":7,\"GuiFocus\":10,"
                + "\"future\":{\"answer\":42}} \r\n";
        StatusSnapshotParser parser = new StatusSnapshotParser();

        StatusSnapshotObservation snapshot = assertInstanceOf(
                ParsedStatusSnapshot.class,
                parser.parse(raw.getBytes(StandardCharsets.UTF_8))
        ).observation();

        assertEquals(raw, snapshot.rawJson());
        assertEquals(
                Instant.parse("2026-07-29T11:59:58Z"),
                snapshot.optionalTimestamp().orElseThrow()
        );
        assertEquals(4_294_967_295L, snapshot.optionalFlags().orElseThrow());
        assertEquals(7L, snapshot.optionalFlags2().orElseThrow());
        assertEquals(10, snapshot.optionalGuiFocus().orElseThrow());
        assertEquals(
                42,
                snapshot.parsedJsonObject()
                        .path("future")
                        .path("answer")
                        .intValue()
        );

        ObjectNode callerCopy =
                (ObjectNode) snapshot.parsedJsonObject();
        callerCopy.remove("future");
        assertTrue(snapshot.parsedJsonObject().has("future"));
    }

    @Test
    void parserAllowsOptionalStatusFieldsButRequiresValidStatusTimestamp() {
        StatusSnapshotParser parser = new StatusSnapshotParser();
        StatusSnapshotObservation minimal = assertInstanceOf(
                ParsedStatusSnapshot.class,
                parser.parse(status("2026-07-29T12:00:00Z", "").getBytes(
                        StandardCharsets.UTF_8
                ))
        ).observation();

        assertTrue(minimal.optionalFlags().isEmpty());
        assertTrue(minimal.optionalFlags2().isEmpty());
        assertTrue(minimal.optionalGuiFocus().isEmpty());

        assertFailureKind(
                parser,
                "{\"event\":\"Status\",\"Flags\":0}",
                StatusParseFailureKind.TIMESTAMP_MISSING
        );
        assertFailureKind(
                parser,
                "{\"timestamp\":\"not-an-instant\",\"event\":\"Status\"}",
                StatusParseFailureKind.TIMESTAMP_INVALID
        );
    }

    @Test
    void parserRejectsWrongEnvelopeInvalidNumbersAndMalformedEncoding() {
        StatusSnapshotParser parser = new StatusSnapshotParser();

        assertFailureKind(
                parser,
                "{\"timestamp\":\"2026-07-29T12:00:00Z\","
                        + "\"event\":\"Journal\",\"Flags\":0}",
                StatusParseFailureKind.WRONG_EVENT
        );
        assertFailureKind(
                parser,
                status(
                        "2026-07-29T12:00:00Z",
                        ",\"Flags\":-1"
                ),
                StatusParseFailureKind.FLAGS_INVALID
        );
        assertFailureKind(
                parser,
                status(
                        "2026-07-29T12:00:00Z",
                        ",\"Flags2\":1.5"
                ),
                StatusParseFailureKind.FLAGS2_INVALID
        );
        assertFailureKind(
                parser,
                status(
                        "2026-07-29T12:00:00Z",
                        ",\"GuiFocus\":2147483648"
                ),
                StatusParseFailureKind.GUI_FOCUS_INVALID
        );
        StatusParseFailure invalidUtf8 = assertInstanceOf(
                StatusParseFailure.class,
                parser.parse(new byte[]{(byte) 0xc3, (byte) 0x28})
        );
        assertEquals(
                StatusParseFailureKind.INVALID_UTF8,
                invalidUtf8.kind()
        );
    }

    @Test
    void adapterIdentityDependsOnBasenameTimestampAndExactContent() {
        StatusSnapshotParser parser = new StatusSnapshotParser();
        StatusSnapshotObservation first = parsed(
                parser,
                status(
                        "2026-07-29T12:00:00Z",
                        ",\"Flags\":0"
                )
        );
        StatusSnapshotObservation same = parsed(
                parser,
                status(
                        "2026-07-29T12:00:00Z",
                        ",\"Flags\":0"
                )
        );
        StatusSnapshotObservation changed = parsed(
                parser,
                status(
                        "2026-07-29T12:00:01Z",
                        ",\"Flags\":4"
                )
        );
        StatusObservationAdapter firstAdapter = adapter("instance-a");
        StatusObservationAdapter secondAdapter = adapter("instance-b");

        ObservationDraft<StatusSnapshotObservation> firstDraft =
                firstAdapter.adapt(
                        first,
                        0,
                        ObservationCaptureMode.BOOTSTRAP,
                        FIXED_CLOCK.instant()
                );
        ObservationDraft<StatusSnapshotObservation> sameDraft =
                secondAdapter.adapt(
                        same,
                        99,
                        ObservationCaptureMode.LIVE,
                        FIXED_CLOCK.instant()
                );
        ObservationDraft<StatusSnapshotObservation> changedDraft =
                firstAdapter.adapt(
                        changed,
                        1,
                        ObservationCaptureMode.LIVE,
                        FIXED_CLOCK.instant()
                );

        assertEquals(firstDraft.observationId(), sameDraft.observationId());
        assertNotEquals(
                firstDraft.observationId(),
                changedDraft.observationId()
        );
        StatusSourcePosition position = assertInstanceOf(
                StatusSourcePosition.class,
                firstDraft.sourcePosition()
        );
        assertEquals("Status.json", position.statusBasename());
        assertEquals(0, position.snapshotSequence());
        assertEquals(first.optionalTimestamp(), firstDraft.sourceTime());
        assertThrows(
                IllegalArgumentException.class,
                () -> new StatusObservationAdapter(
                        firstAdapter.source(),
                        "directory/Status.json"
                )
        );
    }

    @Test
    void watcherPublishesBootstrapAndOnlyChangedValidLiveSnapshots()
            throws Exception {
        Path statusFile = temporaryDirectory.resolve("Status.json");
        String baseline = status(
                "2026-07-29T12:00:00Z",
                ",\"Flags\":0,\"future\":1"
        );
        Files.writeString(statusFile, baseline, StandardCharsets.UTF_8);

        try (InProcessObservationBus bus = new InProcessObservationBus()) {
            List<PublishedObservation<StatusSnapshotObservation>> received =
                    new ArrayList<>();
            ObservationSubscription subscription = bus.subscribe(
                    "status-test",
                    StatusSnapshotObservation.class,
                    received::add
            );
            try (PollingStatusWatcher watcher = watcher(statusFile, bus)) {
                BootstrapPublicationReport bootstrap =
                        watcher.publishBootstrap()
                                .toCompletableFuture()
                                .join();
                assertTrue(bootstrap.successful());
                assertEquals(1, bootstrap.publishedSnapshotCount());
                assertEquals(1, received.size());
                assertEquals(
                        ObservationCaptureMode.BOOTSTRAP,
                        received.getFirst().captureMode()
                );
                assertEquals(0, position(received.getFirst()).snapshotSequence());

                watcher.pollNow().toCompletableFuture().join();
                assertEquals(1, received.size());

                replace(
                        statusFile,
                        "{\"timestamp\":\"2026-07-29T12:00:01Z\","
                                + "\"event\":\"Status\",\"Flags\":"
                );
                watcher.pollNow().toCompletableFuture().join();
                assertEquals(1, received.size());

                String changed = status(
                        "2026-07-29T12:00:02Z",
                        ",\"Flags\":4,\"GuiFocus\":9,\"future\":2"
                );
                replace(statusFile, changed);
                watcher.pollNow().toCompletableFuture().join();
                assertEquals(2, received.size());
                PublishedObservation<StatusSnapshotObservation> live =
                        received.getLast();
                assertEquals(ObservationCaptureMode.LIVE, live.captureMode());
                assertEquals(1, position(live).snapshotSequence());
                assertEquals(changed, live.payload().rawJson());
                assertEquals(4L, live.payload().optionalFlags().orElseThrow());
                assertEquals(
                        9,
                        live.payload().optionalGuiFocus().orElseThrow()
                );
            } finally {
                subscription.close();
            }
        }
    }

    @Test
    void missingBootstrapIsToleratedAndFirstLaterSnapshotIsLiveBaseline()
            throws Exception {
        Path statusFile = temporaryDirectory.resolve("Status.json");
        try (InProcessObservationBus bus = new InProcessObservationBus()) {
            List<PublishedObservation<StatusSnapshotObservation>> received =
                    new ArrayList<>();
            ObservationSubscription subscription = bus.subscribe(
                    "status-test",
                    StatusSnapshotObservation.class,
                    received::add
            );
            try (PollingStatusWatcher watcher = watcher(statusFile, bus)) {
                BootstrapPublicationReport bootstrap =
                        watcher.publishBootstrap()
                                .toCompletableFuture()
                                .join();
                assertTrue(bootstrap.successful());
                assertEquals(0, bootstrap.publishedSnapshotCount());

                Files.writeString(
                        statusFile,
                        status(
                                "2026-07-29T12:00:00Z",
                                ",\"Flags\":0"
                        ),
                        StandardCharsets.UTF_8
                );
                watcher.pollNow().toCompletableFuture().join();

                assertEquals(1, received.size());
                assertEquals(
                        ObservationCaptureMode.LIVE,
                        received.getFirst().captureMode()
                );
                assertEquals(0, position(received.getFirst()).snapshotSequence());
            } finally {
                subscription.close();
            }
        }
    }

    @Test
    void stopAndDrainPublishesFinalChangedSnapshotBeforeCompleting()
            throws Exception {
        Path statusFile = temporaryDirectory.resolve("Status.json");
        Files.writeString(
                statusFile,
                status(
                        "2026-07-29T12:00:00Z",
                        ",\"Flags\":0"
                ),
                StandardCharsets.UTF_8
        );
        try (InProcessObservationBus bus = new InProcessObservationBus()) {
            List<PublishedObservation<StatusSnapshotObservation>> received =
                    new ArrayList<>();
            ObservationSubscription subscription = bus.subscribe(
                    "status-test",
                    StatusSnapshotObservation.class,
                    received::add
            );
            PollingStatusWatcher watcher = watcher(statusFile, bus);
            try {
                watcher.publishBootstrap().toCompletableFuture().join();
                replace(
                        statusFile,
                        status(
                                "2026-07-29T12:00:01Z",
                                ",\"Flags\":4"
                        )
                );

                StatusStopReport stop =
                        watcher.stopAndDrain().toCompletableFuture().join();

                assertTrue(stop.successful());
                assertEquals(2, received.size());
                assertEquals(
                        ObservationCaptureMode.LIVE,
                        received.getLast().captureMode()
                );
                assertEquals(4L, received.getLast()
                        .payload()
                        .optionalFlags()
                        .orElseThrow());
                assertTrue(
                        stop.acceptedHighWaterBusSequence().isPresent()
                );
            } finally {
                watcher.close();
                subscription.close();
            }
        }
    }

    @Test
    void handlerFailureIsReportedWithoutPreventingLaterStatusDelivery()
            throws Exception {
        Path statusFile = temporaryDirectory.resolve("Status.json");
        Files.writeString(
                statusFile,
                status(
                        "2026-07-29T12:00:00Z",
                        ",\"Flags\":0"
                ),
                StandardCharsets.UTF_8
        );
        try (InProcessObservationBus bus = new InProcessObservationBus()) {
            List<String> delivered = new ArrayList<>();
            ObservationSubscription failing = bus.subscribe(
                    "failing-status",
                    StatusSnapshotObservation.class,
                    ignored -> {
                        throw new IllegalStateException("expected");
                    }
            );
            ObservationSubscription healthy = bus.subscribe(
                    "healthy-status",
                    StatusSnapshotObservation.class,
                    observation -> delivered.add(observation.observationId())
            );
            try (PollingStatusWatcher watcher = watcher(statusFile, bus)) {
                BootstrapPublicationReport report =
                        watcher.publishBootstrap()
                                .toCompletableFuture()
                                .join();
                assertTrue(report.successful());
                assertEquals(1, report.handlerFailures().size());
                assertEquals(
                        "failing-status",
                        report.handlerFailures().getFirst().subscriberId()
                );
                assertEquals(1, delivered.size());
            } finally {
                healthy.close();
                failing.close();
            }
        }
    }

    @Test
    void publicationFailureCompletesTerminalFailureAndStopReport()
            throws Exception {
        Path statusFile = temporaryDirectory.resolve("Status.json");
        Files.writeString(
                statusFile,
                status(
                        "2026-07-29T12:00:00Z",
                        ",\"Flags\":0"
                ),
                StandardCharsets.UTF_8
        );
        InProcessObservationBus bus = new InProcessObservationBus();
        PollingStatusWatcher watcher = watcher(statusFile, bus);
        try {
            watcher.publishBootstrap().toCompletableFuture().join();
            bus.close();
            replace(
                    statusFile,
                    status(
                            "2026-07-29T12:00:01Z",
                            ",\"Flags\":4"
                    )
            );

            assertThrows(
                    CompletionException.class,
                    () -> watcher.pollNow().toCompletableFuture().join()
            );
            Throwable terminal = watcher.terminalFailure()
                    .toCompletableFuture()
                    .join();
            assertInstanceOf(
                    PollingStatusWatcher.StatusPublicationException.class,
                    terminal
            );
            StatusStopReport stop =
                    watcher.stopAndDrain().toCompletableFuture().join();
            assertFalse(stop.successful());
            assertTrue(stop.failure().isPresent());
        } finally {
            watcher.close();
            bus.close();
        }
    }

    private PollingStatusWatcher watcher(
            Path statusFile,
            InProcessObservationBus bus
    ) {
        return new PollingStatusWatcher(
                statusFile,
                new StatusSnapshotParser(),
                adapter("watcher"),
                bus,
                FIXED_CLOCK,
                Duration.ofHours(1)
        );
    }

    private static StatusObservationAdapter adapter(String instanceId) {
        return new StatusObservationAdapter(
                new ObservationSource(
                        "elite-dangerous-status",
                        instanceId
                ),
                "Status.json"
        );
    }

    private void replace(Path target, String content) throws Exception {
        Path replacement = temporaryDirectory.resolve(
                "Status-replacement-" + System.nanoTime() + ".json"
        );
        Files.writeString(replacement, content, StandardCharsets.UTF_8);
        Files.move(
                replacement,
                target,
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    private static StatusSnapshotObservation parsed(
            StatusSnapshotParser parser,
            String raw
    ) {
        return assertInstanceOf(
                ParsedStatusSnapshot.class,
                parser.parse(raw.getBytes(StandardCharsets.UTF_8))
        ).observation();
    }

    private static StatusSourcePosition position(
            PublishedObservation<StatusSnapshotObservation> observation
    ) {
        return assertInstanceOf(
                StatusSourcePosition.class,
                observation.sourcePosition()
        );
    }

    private static void assertFailureKind(
            StatusSnapshotParser parser,
            String raw,
            StatusParseFailureKind expected
    ) {
        StatusParseFailure failure = assertInstanceOf(
                StatusParseFailure.class,
                parser.parse(raw.getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(expected, failure.kind());
    }

    private static String status(String timestamp, String additionalFields) {
        return "{\"timestamp\":\"" + timestamp
                + "\",\"event\":\"Status\""
                + additionalFields
                + '}';
    }
}
