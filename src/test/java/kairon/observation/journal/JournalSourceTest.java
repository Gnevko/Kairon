package kairon.observation.journal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.ObservationPayload;
import kairon.observation.bus.ObservationBus;
import kairon.observation.bus.ObservationBus.ObservationHandler;
import kairon.observation.bus.ObservationBus.ObservationSubscription;
import kairon.observation.bus.ObservationBus.PublishReceipt;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.JournalParseFailure;
import kairon.observation.journal.JournalLineParser.JournalParseFailureKind;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.JournalObservationAdapter.JournalSourcePosition;
import kairon.observation.journal.event.travel.FSDJump;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.source.ObservationSourceSignal.ObservationSourceSignalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalSourceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void journalCatalogueMapsKnownAndUnknownWhileParserPreservesRawRecords()
            throws Exception {
        JournalLineParser parser = new JournalLineParser();
        String rawJson = " {\"timestamp\":\"2026-07-28T11:59:00Z\","
                + "\"event\":\"FutureEvent\",\"unknown\":{\"answer\":42}} \t";

        ParsedJournalRecord parsed = assertInstanceOf(
                ParsedJournalRecord.class,
                parser.parse(record(17, rawJson))
        );
        assertEquals(rawJson, parsed.rawJson());
        assertEquals("FutureEvent", parsed.optionalEventType().orElseThrow());
        assertEquals(
                Instant.parse("2026-07-28T11:59:00Z"),
                parsed.optionalJournalTimestamp().orElseThrow()
        );
        assertEquals(42, parsed.parsedJsonObject().path("unknown").path("answer").intValue());

        ObjectNode callerCopy = (ObjectNode) parsed.parsedJsonObject();
        callerCopy.put("mutated", true);
        assertFalse(parsed.parsedJsonObject().has("mutated"));

        RawJournalData payload = new RawJournalData(
                parsed.rawJson(),
                parsed.parsedJsonObject(),
                parsed.optionalEventType(),
                parsed.optionalJournalTimestamp()
        );
        ((ObjectNode) payload.parsedJsonObject()).remove("unknown");
        assertTrue(payload.parsedJsonObject().has("unknown"));

        assertEquals(272, JournalEventCatalog.knownEventTypes().size());
        assertEquals(
                "33a8f35e81868b168b4bbd647b5e13dbd8de062a",
                JournalEventCatalog.PINNED_SCHEMA_REVISION
        );
        assertEquals(
                "7d845f64ec0c933c1de239b25e07064dce3fa4cdd629d9abaee1db213556784d",
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(
                                JournalEventCatalog.knownEventTypes().stream()
                                        .sorted()
                                        .collect(Collectors.joining("\n"))
                                        .getBytes(StandardCharsets.UTF_8)
                        )
                )
        );
        Set<Class<? extends JournalEventObservation>> concreteTypes = new HashSet<>();
        long catalogOffset = 1_000;
        for (String eventType : JournalEventCatalog.knownEventTypes()) {
            String catalogRawJson = "{\"event\":\"" + eventType
                    + "\",\"unknown\":{\"preserved\":true}}";
            ParsedJournalRecord catalogRecord =
                    parsed(parser, catalogOffset++, catalogRawJson);
            JournalEventObservation mapped =
                    JournalEventCatalog.create(rawData(catalogRecord));
            Class<? extends JournalEventObservation> expectedType =
                    JournalEventCatalog.payloadTypeFor(eventType);

            assertSame(expectedType, mapped.getClass(), eventType);
            assertEquals(eventType, expectedType.getSimpleName(), eventType);
            assertTrue(expectedType.isRecord(), eventType);
            assertTrue(Modifier.isPublic(expectedType.getModifiers()), eventType);
            assertNull(expectedType.getEnclosingClass(), eventType);
            assertTrue(
                    expectedType.getPackageName().startsWith(
                            "kairon.observation.journal.event."
                    ),
                    eventType
            );
            assertEquals(catalogRawJson, mapped.raw().rawJson(), eventType);
            assertTrue(
                    mapped.raw().parsedJsonObject()
                            .path("unknown")
                            .path("preserved")
                            .booleanValue(),
                    eventType
            );
            concreteTypes.add(mapped.getClass());
        }
        assertEquals(272, concreteTypes.size());

        for (String rawUnknown : List.of(
                "{\"event\":\"Status\"}",
                "{\"event\":\"FutureUnknownEvent\"}",
                "{\"event\":\"\"}",
                "{\"missingEvent\":true}",
                "{\"event\":42}"
        )) {
            ParsedJournalRecord unknownRecord =
                    parsed(parser, catalogOffset++, rawUnknown);
            JournalEventObservation unknown =
                    JournalEventCatalog.create(rawData(unknownRecord));
            assertInstanceOf(UnknownJournalEvent.class, unknown);
            assertEquals(rawUnknown, unknown.raw().rawJson());
        }
        assertSame(
                UnknownJournalEvent.class,
                JournalEventCatalog.payloadTypeFor("FutureUnknownEvent")
        );
        ParsedJournalRecord wrongCaseRecord =
                parsed(parser, catalogOffset, "{\"event\":\"FsdJump\"}");
        assertThrows(
                IllegalArgumentException.class,
                () -> new FSDJump(rawData(wrongCaseRecord))
        );
        ParsedJournalRecord dockedRecord =
                parsed(parser, catalogOffset + 1, "{\"event\":\"Docked\"}");
        assertThrows(
                IllegalArgumentException.class,
                () -> new RawJournalData(
                        "{\"event\":\"Docked\"}",
                        dockedRecord.parsedJsonObject(),
                        Optional.of("FSDJump"),
                        Optional.empty()
                )
        );
        ParsedJournalRecord fsdRecord =
                parsed(parser, catalogOffset + 2, "{\"event\":\"FSDJump\"}");
        assertThrows(
                IllegalArgumentException.class,
                () -> new RawJournalData(
                        "{\"event\":\"Docked\"}",
                        fsdRecord.parsedJsonObject(),
                        Optional.of("FSDJump"),
                        Optional.empty()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new UnknownJournalEvent(rawData(fsdRecord))
        );

        JournalParseFailure invalidUtf8 = assertInstanceOf(
                JournalParseFailure.class,
                parser.parse(new CompleteJournalRecord(
                        "Journal.01.log",
                        0,
                        new byte[]{(byte) 0xC3, (byte) 0x28}
                ))
        );
        assertEquals(JournalParseFailureKind.INVALID_UTF8, invalidUtf8.kind());
        assertEquals(
                JournalParseFailureKind.TRAILING_TOKEN,
                assertInstanceOf(
                        JournalParseFailure.class,
                        parser.parse(record(0, "{} {}"))
                ).kind()
        );
        assertEquals(
                JournalParseFailureKind.NOT_AN_OBJECT,
                assertInstanceOf(
                        JournalParseFailure.class,
                        parser.parse(record(0, "[]"))
                ).kind()
        );
    }

    @Test
    void adapterUsesStableOffsetIdentityRejectsDuplicateAndPublishesInSourceOrder() {
        JournalLineParser parser = new JournalLineParser();
        ParsedJournalRecord first = parsed(parser, 0, "{\"event\":\"FSDJump\"}");
        ParsedJournalRecord second = parsed(parser, 20, "{\"event\":\"Docked\"}");
        ObservationSource source =
                new ObservationSource("elite-dangerous-journal", "source-test");
        JournalObservationAdapter liveAdapter = new JournalObservationAdapter(source);

        ObservationDraft<JournalEventObservation> liveDraft =
                liveAdapter.adapt(first, ObservationCaptureMode.LIVE, FIXED_CLOCK.instant());
        liveAdapter.commit(liveDraft.observationId());
        assertEquals(
                JournalObservationAdapter.journalObservationId("Journal.01.log", 0),
                liveDraft.observationId()
        );
        assertInstanceOf(FSDJump.class, liveDraft.payload());
        assertThrows(
                JournalObservationAdapter.ExactDuplicateJournalObservationException.class,
                () -> liveAdapter.adapt(
                        first,
                        ObservationCaptureMode.LIVE,
                        FIXED_CLOCK.instant()
                )
        );
        ParsedJournalRecord changedAtSameOffset =
                parsed(parser, 0, "{\"event\":\"Changed\"}");
        assertThrows(
                JournalObservationAdapter.ObservationIdentityCollisionException.class,
                () -> liveAdapter.adapt(
                        changedAtSameOffset,
                        ObservationCaptureMode.LIVE,
                        FIXED_CLOCK.instant()
                )
        );

        JournalObservationAdapter replayAdapter = new JournalObservationAdapter(source);
        ObservationDraft<JournalEventObservation> replayFirst =
                replayAdapter.adapt(first, ObservationCaptureMode.REPLAY, FIXED_CLOCK.instant());
        assertEquals(liveDraft.observationId(), replayFirst.observationId());

        RecordingBus bus = new RecordingBus();
        PublishReceipt firstReceipt = bus.publish(replayFirst).toCompletableFuture().join();
        replayAdapter.commit(replayFirst.observationId());
        ObservationDraft<JournalEventObservation> replaySecond =
                replayAdapter.adapt(second, ObservationCaptureMode.REPLAY, FIXED_CLOCK.instant());
        PublishReceipt secondReceipt = bus.publish(replaySecond).toCompletableFuture().join();
        replayAdapter.commit(replaySecond.observationId());

        assertEquals(1, firstReceipt.busSequence());
        assertEquals(2, secondReceipt.busSequence());
        assertEquals(
                List.of(0L, 20L),
                bus.accepted().stream()
                        .map(ObservationDraft::sourcePosition)
                        .map(JournalSourcePosition.class::cast)
                        .map(JournalSourcePosition::zeroBasedSourceByteOffset)
                        .toList()
        );
    }

    @Test
    void bootstrapKeepsLastThirtyAndBoundaryPartialBecomesLiveBeforeBoundedRotation(
            @TempDir Path directory
    )
            throws Exception {
        Path journal = directory.resolve("Journal.2026-07-28T120000.01.log");
        StringBuilder initial = new StringBuilder();
        for (int index = 0; index < 32; index++) {
            initial.append("{\"event\":\"Event")
                    .append(index)
                    .append("\"}\n");
        }
        long partialOffset = initial.toString().getBytes(StandardCharsets.UTF_8).length;
        initial.append("{\"event\":\"Live");
        Files.writeString(journal, initial, StandardCharsets.UTF_8);

        RecordingBus bus = new RecordingBus();
        MutableClock clock = new MutableClock(FIXED_CLOCK.instant());
        JournalObservationAdapter adapter = new JournalObservationAdapter(
                new ObservationSource("elite-dangerous-journal", "live-source")
        );
        try (PollingJournalTailReader source = new PollingJournalTailReader(
                directory,
                new JournalLineParser(),
                adapter,
                bus,
                clock
        )) {
            var report = source.publishBootstrap().toCompletableFuture().join();
            assertTrue(report.successful());
            assertEquals(30, report.selectedRecordCount());
            assertEquals(30, report.publishedRecordCount());
            assertEquals(30, bus.accepted().size());
            assertEquals(
                    "Event2",
                    ((JournalEventObservation) bus.accepted().getFirst().payload())
                            .raw()
                            .optionalEventType()
                            .orElseThrow()
            );
            assertTrue(
                    bus.accepted().stream()
                            .allMatch(draft ->
                                    draft.captureMode() == ObservationCaptureMode.BOOTSTRAP)
            );

            Files.writeString(
                    journal,
                    "\"}\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND
            );
            source.pollNow().toCompletableFuture().join();

            assertEquals(31, bus.accepted().size());
            ObservationDraft<?> liveDraft = bus.accepted().getLast();
            assertEquals(ObservationCaptureMode.LIVE, liveDraft.captureMode());
            assertEquals(
                    partialOffset,
                    ((JournalSourcePosition) liveDraft.sourcePosition())
                            .zeroBasedSourceByteOffset()
            );
            assertEquals(
                    "{\"event\":\"Live\"}",
                    ((JournalEventObservation) liveDraft.payload()).raw().rawJson()
            );

            long malformedOffset = Files.size(journal);
            String malformedRecord = "{\"event\":broken}\n";
            Files.writeString(
                    journal,
                    malformedRecord + "{\"event\":\"AfterMalformed\"}\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND
            );
            source.pollNow().toCompletableFuture().join();
            assertEquals(32, bus.accepted().size());
            ObservationDraft<?> afterMalformed = bus.accepted().getLast();
            assertEquals(
                    malformedOffset + malformedRecord.getBytes(StandardCharsets.UTF_8).length,
                    ((JournalSourcePosition) afterMalformed.sourcePosition())
                            .zeroBasedSourceByteOffset()
            );
            assertEquals(
                    "AfterMalformed",
                    ((JournalEventObservation) afterMalformed.payload())
                            .raw()
                            .optionalEventType()
                            .orElseThrow()
            );

            Files.writeString(
                    journal,
                    "{\"event\":\"AbandonedTail\"",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND
            );
            Path successor = directory.resolve("Journal.2026-07-28T120100.01.log");
            Files.writeString(
                    successor,
                    "{\"event\":\"Successor\"}\n",
                    StandardCharsets.UTF_8
            );
            source.pollNow().toCompletableFuture().join();
            assertEquals(32, bus.accepted().size());

            clock.advance(PollingJournalTailReader.ROTATION_PARTIAL_LINE_TIMEOUT);
            source.pollNow().toCompletableFuture().join();
            assertEquals(33, bus.accepted().size());
            ObservationDraft<?> successorDraft = bus.accepted().getLast();
            assertEquals(
                    successor.getFileName().toString(),
                    ((JournalSourcePosition) successorDraft.sourcePosition())
                            .journalBasename()
            );
            assertEquals(
                    "{\"event\":\"Successor\"}",
                    ((JournalEventObservation) successorDraft.payload()).raw().rawJson()
            );

            Path replacement = directory.resolve("replacement.tmp");
            Files.writeString(
                    replacement,
                    "{\"event\":\"Replaced!\"}\n"
                            + "{\"event\":\"MustNotPublish\"}\n",
                    StandardCharsets.UTF_8
            );
            Files.move(replacement, successor, StandardCopyOption.REPLACE_EXISTING);
            source.pollNow().toCompletableFuture().join();
            assertEquals(33, bus.accepted().size());

            Path recovery = directory.resolve("Journal.2026-07-28T120200.01.log");
            Files.writeString(
                    recovery,
                    "{\"event\":\"Recovery\"}\n",
                    StandardCharsets.UTF_8
            );
            source.pollNow().toCompletableFuture().join();
            assertEquals(34, bus.accepted().size());

            bus.drainAndClose().toCompletableFuture().join();
            Files.writeString(
                    recovery,
                    "{\"event\":\"AfterBusFailure\"}\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND
            );
            assertThrows(
                    CompletionException.class,
                    () -> source.pollNow().toCompletableFuture().join()
            );
            assertEquals(
                    "SourcePublicationException",
                    source.terminalFailure().toCompletableFuture().join()
                            .getClass().getSimpleName()
            );
        }
    }

    @Test
    void replayPublishesRecordsThenExhaustionSignalThroughSameBus(@TempDir Path directory)
            throws Exception {
        Path journal = directory.resolve("Journal.replay.log");
        String content = "{\"event\":\"UnknownFuture\",\"extra\":true}\r\n"
                + "{\"event\":\"Second\"}\n"
                + "{\"event\":\"Incomplete\"}";
        Files.writeString(journal, content, StandardCharsets.UTF_8);
        long fileSize = Files.size(journal);

        RecordingBus bus = new RecordingBus();
        ObservationSource observationSource =
                new ObservationSource("elite-dangerous-journal", "replay-source");
        JournalObservationAdapter adapter = new JournalObservationAdapter(observationSource);
        JournalReplaySource.ReplayReport report;
        try (JournalReplaySource source = new JournalReplaySource(
                journal,
                new JournalLineParser(),
                adapter,
                bus,
                FIXED_CLOCK
        )) {
            report = source.publishAll().toCompletableFuture().join();
        }

        assertTrue(report.successful());
        assertFalse(report.cancelled());
        assertTrue(report.exhaustionSignalAccepted());
        assertEquals(2, report.publishedRecordCount());
        assertEquals(3, report.acceptedHighWaterBusSequence().orElseThrow());
        assertEquals(3, bus.accepted().size());
        assertEquals(
                List.of(ObservationCaptureMode.REPLAY, ObservationCaptureMode.REPLAY),
                bus.accepted().subList(0, 2).stream()
                        .map(ObservationDraft::captureMode)
                        .toList()
        );
        assertEquals(
                "{\"event\":\"UnknownFuture\",\"extra\":true}",
                ((JournalEventObservation) bus.accepted().getFirst().payload())
                        .raw()
                        .rawJson()
        );

        ObservationDraft<?> signalDraft = bus.accepted().getLast();
        ObservationSourceSignal signal =
                assertInstanceOf(ObservationSourceSignal.class, signalDraft.payload());
        assertEquals(
                ObservationSourceSignalType.REPLAY_SOURCE_EXHAUSTED,
                signal.signalType()
        );
        assertEquals(ObservationCaptureMode.REPLAY, signalDraft.captureMode());
        assertEquals(
                fileSize,
                ((JournalSourcePosition) signalDraft.sourcePosition())
                        .zeroBasedSourceByteOffset()
        );
        assertEquals(
                JournalReplaySource.replayExhaustedObservationId(
                        observationSource.sourceInstanceId(),
                        journal.getFileName().toString(),
                        fileSize
                ),
                signalDraft.observationId()
        );
    }

    @Test
    void pacedReplayUsesRecordedGapsWithCapAndPreservesOrderAndRawJson(
            @TempDir Path directory
    ) throws Exception {
        Path journal = directory.resolve("Journal.paced.log");
        List<String> rawRecords = List.of(
                """
                {"timestamp":"2026-07-28T12:00:00Z","event":"First","marker":1}
                """.strip(),
                """
                {"timestamp":"2026-07-28T12:00:03Z","event":"Second","marker":2}
                """.strip(),
                """
                {"timestamp":"2026-07-28T12:00:30Z","event":"Third","marker":3}
                """.strip(),
                """
                {"timestamp":"2026-07-28T12:00:20Z","event":"Backward","marker":4}
                """.strip(),
                """
                {"timestamp":"2026-07-28T12:00:20Z","event":"Equal","marker":5}
                """.strip(),
                """
                {"timestamp":"not-an-instant","event":"InvalidTimestamp","marker":6}
                """.strip(),
                """
                {"timestamp":"2026-07-28T12:00:50Z","event":"AfterReset","marker":7}
                """.strip(),
                """
                {"event":"MissingTimestamp","marker":8}
                """.strip(),
                """
                {"timestamp":"2026-07-28T12:01:00Z","event":"AfterMissing","marker":9}
                """.strip(),
                """
                {"timestamp":"2026-07-28T12:01:02Z","event":"Last","marker":10}
                """.strip()
        );
        Files.writeString(
                journal,
                String.join("\n", rawRecords) + '\n',
                StandardCharsets.UTF_8
        );

        RecordingBus bus = new RecordingBus();
        JournalObservationAdapter adapter = new JournalObservationAdapter(
                new ObservationSource(
                        "elite-dangerous-journal",
                        "paced-replay-source"
                )
        );
        List<Duration> pauses = new ArrayList<>();
        JournalReplaySource.ReplayReport report;
        try (JournalReplaySource source = new JournalReplaySource(
                journal,
                new JournalLineParser(),
                adapter,
                bus,
                FIXED_CLOCK,
                pauses::add
        )) {
            report = source.publishAll().toCompletableFuture().join();
        }

        assertTrue(report.successful());
        assertFalse(report.cancelled());
        assertEquals(
                List.of(
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(6),
                        Duration.ofSeconds(2)
                ),
                pauses,
                "a long journal gap is capped at the maximum pause"
        );
        assertEquals(rawRecords.size() + 1, bus.accepted().size());
        assertEquals(
                rawRecords,
                bus.accepted().subList(0, rawRecords.size()).stream()
                        .map(ObservationDraft::payload)
                        .map(JournalEventObservation.class::cast)
                        .map(JournalEventObservation::raw)
                        .map(RawJournalData::rawJson)
                        .toList()
        );
        assertInstanceOf(
                ObservationSourceSignal.class,
                bus.accepted().getLast().payload()
        );
    }

    @Test
    void stoppingPacedReplayInterruptsPendingDelayWithoutPublishingExhaustion(
            @TempDir Path directory
    ) throws Exception {
        Path journal = directory.resolve("Journal.cancelled-replay.log");
        Files.writeString(
                journal,
                """
                {"timestamp":"2026-07-28T12:00:00Z","event":"First"}
                {"timestamp":"2026-07-28T12:00:09Z","event":"Second"}
                {"timestamp":"2026-07-28T12:00:10Z","event":"Third"}
                """,
                StandardCharsets.UTF_8
        );

        RecordingBus bus = new RecordingBus();
        JournalObservationAdapter adapter = new JournalObservationAdapter(
                new ObservationSource(
                        "elite-dangerous-journal",
                        "cancelled-replay-source"
                )
        );
        CountDownLatch delayStarted = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        JournalReplaySource.ReplayReport report;
        try (JournalReplaySource source = new JournalReplaySource(
                journal,
                new JournalLineParser(),
                adapter,
                bus,
                FIXED_CLOCK,
                ignored -> {
                    delayStarted.countDown();
                    neverReleased.await();
                }
        )) {
            CompletableFuture<JournalReplaySource.ReplayReport> replay =
                    source.publishAll().toCompletableFuture();
            assertTrue(
                    delayStarted.await(2, TimeUnit.SECONDS),
                    "replay did not enter the pending journal-time delay"
            );
            assertTrue(source.requestStop());
            report = replay.get(2, TimeUnit.SECONDS);
        }

        assertTrue(report.cancelled());
        assertFalse(report.successful());
        assertFalse(report.exhaustionSignalAccepted());
        assertTrue(report.failure().isEmpty());
        assertEquals(1, report.publishedRecordCount());
        assertEquals(1, report.acceptedHighWaterBusSequence().orElseThrow());
        assertEquals(1, bus.accepted().size());
        assertInstanceOf(
                JournalEventObservation.class,
                bus.accepted().getFirst().payload()
        );
        assertFalse(bus.accepted().stream().anyMatch(
                draft -> draft.payload() instanceof ObservationSourceSignal
        ));
    }

    private static CompleteJournalRecord record(long offset, String rawJson) {
        return new CompleteJournalRecord(
                "Journal.01.log",
                offset,
                rawJson.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static ParsedJournalRecord parsed(
            JournalLineParser parser,
            long offset,
            String rawJson
    ) {
        return assertInstanceOf(ParsedJournalRecord.class, parser.parse(record(offset, rawJson)));
    }

    private static RawJournalData rawData(ParsedJournalRecord record) {
        return new RawJournalData(
                record.rawJson(),
                record.parsedJsonObject(),
                record.optionalEventType(),
                record.optionalJournalTimestamp()
        );
    }

    private static final class RecordingBus implements ObservationBus {

        private final List<ObservationDraft<? extends ObservationPayload>> accepted =
                new ArrayList<>();
        private long nextSequence = 1;
        private boolean closed;

        @Override
        public <T extends ObservationPayload> ObservationSubscription subscribe(
                String subscriberId,
                Class<T> payloadType,
                ObservationHandler<T> handler
        ) {
            throw new UnsupportedOperationException("subscriptions are not needed by this fixture");
        }

        @Override
        public synchronized <T extends ObservationPayload> CompletionStage<PublishReceipt> publish(
                ObservationDraft<T> observation
        ) {
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("bus is closed")
                );
            }
            accepted.add(observation);
            long sequence = nextSequence++;
            return CompletableFuture.completedFuture(new PublishReceipt(
                    observation.observationId(),
                    sequence,
                    List.of(),
                    List.of()
            ));
        }

        @Override
        public synchronized CompletionStage<Void> drainAndClose() {
            closed = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public synchronized void close() {
            closed = true;
        }

        synchronized List<ObservationDraft<? extends ObservationPayload>> accepted() {
            return List.copyOf(accepted);
        }
    }

    private static final class MutableClock extends Clock {

        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("fixture uses UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
