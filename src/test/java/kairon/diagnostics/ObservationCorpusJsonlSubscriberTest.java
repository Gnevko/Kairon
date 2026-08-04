package kairon.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.ObservationDraft.SourcePosition;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.status.StatusSnapshotObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ObservationCorpusJsonlSubscriberTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObservationSource SOURCE =
            new ObservationSource("journal-source", "journal-instance");
    private static final String JOURNAL_ID = "journal-id";
    private static final String STATUS_ID = "status-id";

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesJournalRecordWithExpectedEnvelopeAndRawJson() throws Exception {
        StringWriter writer = new StringWriter();
        String rawJson = "{\"event\":\"FSDJump\",\"fuel\":123}";
        Instant sourceTime = Instant.parse("2026-07-30T09:15:30Z");
        Instant observedAt = Instant.parse("2026-07-30T09:16:30Z");

        try (ObservationCorpusJsonlSubscriber subscriber =
                new ObservationCorpusJsonlSubscriber(writer)) {
            subscriber.onNext(journalObservation(
                    "journal-1",
                    10L,
                    rawJson,
                    sourceTime,
                    observedAt
            ));
        }

        List<JsonNode> lines = parseJsonLines(writer.toString());
        assertEquals(1, lines.size());
        JsonNode corpusLine = lines.getFirst();

        assertEquals(
                "kairon-observation-corpus-v1",
                corpusLine.get("schemaVersion").asText()
        );
        assertEquals("journal-1", corpusLine.get("observationId").asText());
        assertEquals(10L, corpusLine.get("busSequence").asLong());
        assertEquals(SOURCE.toString(), corpusLine.get("source").asText());
        assertEquals(journalPosition(10L).toString(),
                corpusLine.get("sourcePosition").asText());
        assertEquals(sourceTime.toString(), corpusLine.get("sourceTime").asText());
        assertEquals(observedAt.toString(), corpusLine.get("observedAt").asText());
        assertEquals("LIVE", corpusLine.get("captureMode").asText());
        assertEquals("kairon.journal-event-observation/v1",
                corpusLine.get("payloadSchemaVersion").asText());
        assertEquals("JOURNAL", corpusLine.get("observationKind").asText());
        assertEquals("FixtureJournalEvent",
                corpusLine.get("payloadType").asText());
        assertEquals("FSDJump", corpusLine.get("eventType").asText());
        assertEquals(rawJson, corpusLine.get("rawJson").asText());
    }

    @Test
    void writesStatusRecordWithStatusSnapshotKindAndEnvelopeMetadata() throws Exception {
        StringWriter writer = new StringWriter();
        String rawJson = "{\"timestamp\":\"2026-07-30T11:00:00Z\",\"Flags\":1}";
        Instant sourceTime = Instant.parse("2026-07-30T11:00:00Z");
        Instant observedAt = Instant.parse("2026-07-30T11:01:00Z");

        try (ObservationCorpusJsonlSubscriber subscriber =
                new ObservationCorpusJsonlSubscriber(writer)) {
            subscriber.onNext(statusObservation(
                    "status-1",
                    11L,
                    rawJson,
                    sourceTime,
                    observedAt
            ));
        }

        List<JsonNode> lines = parseJsonLines(writer.toString());
        assertEquals(1, lines.size());
        JsonNode corpusLine = lines.getFirst();

        assertEquals("STATUS", corpusLine.get("observationKind").asText());
        assertEquals("StatusSnapshot", corpusLine.get("eventType").asText());
        assertEquals("kairon.status-snapshot-observation/v1",
                corpusLine.get("payloadSchemaVersion").asText());
        assertEquals(rawJson, corpusLine.get("rawJson").asText());
        assertEquals("status-1", corpusLine.get("observationId").asText());
        assertEquals(
                statusPosition(11L).toString(),
                corpusLine.get("sourcePosition").asText()
        );
        assertEquals(sourceTime.toString(), corpusLine.get("sourceTime").asText());
        assertEquals(observedAt.toString(), corpusLine.get("observedAt").asText());
    }

    @Test
    void writesBusSequenceInArrivalOrderWithoutReordering() throws Exception {
        StringWriter writer = new StringWriter();
        try (ObservationCorpusJsonlSubscriber subscriber =
                new ObservationCorpusJsonlSubscriber(writer)) {
            subscriber.onNext(journalObservation(
                    JOURNAL_ID,
                    10L,
                    "{\"event\":\"Journal10\"}",
                    Instant.parse("2026-07-30T10:00:00Z"),
                    Instant.parse("2026-07-30T10:00:10Z")
            ));
            subscriber.onNext(statusObservation(
                    STATUS_ID,
                    11L,
                    "{\"timestamp\":\"2026-07-30T10:00:20Z\"}",
                    Instant.parse("2026-07-30T10:00:20Z"),
                    Instant.parse("2026-07-30T10:00:21Z")
            ));
            subscriber.onNext(journalObservation(
                    JOURNAL_ID,
                    12L,
                    "{\"event\":\"Journal12\"}",
                    Instant.parse("2026-07-30T10:00:30Z"),
                    Instant.parse("2026-07-30T10:00:31Z")
            ));
        }

        List<JsonNode> lines = parseJsonLines(writer.toString());
        assertEquals(3, lines.size());
        assertEquals(List.of(10L, 11L, 12L), lines.stream()
                .map(node -> node.get("busSequence").longValue())
                .toList());
        assertEquals(List.of("JOURNAL", "STATUS", "JOURNAL"), lines.stream()
                .map(node -> node.get("observationKind").asText())
                .toList());
    }

    @Test
    void ignoresUnsupportedPayloadAndStillWritesFollowingSupportedPayload() throws Exception {
        StringWriter writer = new StringWriter();
        try (ObservationCorpusJsonlSubscriber subscriber =
                new ObservationCorpusJsonlSubscriber(writer)) {
            subscriber.onNext(new PublishedObservation<>(
                    "unsupported",
                    10L,
                    SOURCE,
                    journalPosition(10L),
                    Optional.of(Instant.parse("2026-07-30T12:00:00Z")),
                    Instant.parse("2026-07-30T12:00:01Z"),
                    ObservationCaptureMode.REPLAY,
                    ObservationSourceSignal.SCHEMA_VERSION,
                    new ObservationSourceSignal(
                            ObservationSourceSignal.ObservationSourceSignalType.REPLAY_SOURCE_EXHAUSTED
                    )
            ));
            subscriber.onNext(journalObservation(
                    "journal-follow-up",
                    11L,
                    "{\"event\":\"Docked\",\"future\":true}",
                    Instant.parse("2026-07-30T12:00:10Z"),
                    Instant.parse("2026-07-30T12:00:11Z")
            ));
        }
        List<JsonNode> lines = parseJsonLines(writer.toString());
        assertEquals(1, lines.size());
        assertEquals(11L, lines.getFirst().get("busSequence").asLong());
        assertEquals("JOURNAL", lines.getFirst().get("observationKind").asText());
    }

    @Test
    void preservesEscapingInRawJsonRoundtrip() throws Exception {
        String rawJson = "{\"event\":\"Quote\",\"text\":\"A \\\\\\\"quoted\\\\\\\" value with \\\\\\\\slashes and Unicode \\u03a9\","
                + "\"note\":\"\\u043f\\u0440\\u0438\\u0432\\u0435\\u0442\"}";
        StringWriter writer = new StringWriter();

        try (ObservationCorpusJsonlSubscriber subscriber =
                new ObservationCorpusJsonlSubscriber(writer)) {
            subscriber.onNext(journalObservation(
                    "escaping-journal",
                    20L,
                    rawJson,
                    Instant.parse("2026-07-30T13:00:00Z"),
                    Instant.parse("2026-07-30T13:00:01Z")
            ));
        }

        List<JsonNode> lines = parseJsonLines(writer.toString());
        assertEquals(1, lines.size());
        JsonNode line = lines.getFirst();

        assertEquals("JOURNAL", line.get("observationKind").asText());
        assertEquals(rawJson, line.get("rawJson").asText());
    }

    @Test
    void closeIsIdempotentAndRejectsWritesAfterClose() throws Exception {
        StringWriter writer = new StringWriter();
        ObservationCorpusJsonlSubscriber subscriber =
                new ObservationCorpusJsonlSubscriber(writer);
        subscriber.close();
        subscriber.close();

        assertThrows(IllegalStateException.class, () ->
                subscriber.onNext(journalObservation(
                        JOURNAL_ID,
                        1L,
                        "{\"event\":\"FSDJump\"}",
                        Instant.parse("2026-07-30T14:00:00Z"),
                        Instant.parse("2026-07-30T14:00:01Z")
                ))
        );
    }

    @Test
    void pathConstructorCreatesParentAndAppendsWithoutLosingPriorData() throws Exception {
        Path corpus = temporaryDirectory.resolve("nested/path/corpus.jsonl");
        try (ObservationCorpusJsonlSubscriber first = new ObservationCorpusJsonlSubscriber(corpus)) {
            assertTrue(Files.exists(corpus.getParent()));
            first.onNext(journalObservation(
                    "journal-append-1",
                    1L,
                    "{\"event\":\"StartJump\"}",
                    Instant.parse("2026-07-30T15:00:00Z"),
                    Instant.parse("2026-07-30T15:00:01Z")
            ));
        }

        assertTrue(Files.exists(corpus.getParent()));
        assertTrue(Files.exists(corpus));
        assertEquals(1, Files.readAllLines(corpus, StandardCharsets.UTF_8).size());

        try (ObservationCorpusJsonlSubscriber second = new ObservationCorpusJsonlSubscriber(corpus)) {
            second.onNext(journalObservation(
                    "journal-append-2",
                    2L,
                    "{\"event\":\"EndJump\"}",
                    Instant.parse("2026-07-30T16:00:00Z"),
                    Instant.parse("2026-07-30T16:00:01Z")
            ));
        }

        List<String> lines = Files.readAllLines(corpus, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals("JOURNAL", JSON.readTree(lines.get(0)).get("observationKind").asText());
        assertEquals("JOURNAL", JSON.readTree(lines.get(1)).get("observationKind").asText());
    }

    private static PublishedObservation<JournalEventObservation> journalObservation(
            String observationId,
            long busSequence,
            String rawJson,
            Instant sourceTime,
            Instant observedAt
    ) {
        return new PublishedObservation<>(
                observationId,
                busSequence,
                SOURCE,
                journalPosition(busSequence),
                Optional.ofNullable(sourceTime),
                observedAt,
                ObservationCaptureMode.LIVE,
                JournalEventObservation.SCHEMA_VERSION,
                new FixtureJournalEvent(rawJournal(rawJson))
        );
    }

    private static PublishedObservation<StatusSnapshotObservation> statusObservation(
            String observationId,
            long busSequence,
            String rawJson,
            Instant sourceTime,
            Instant observedAt
    ) {
        return new PublishedObservation<>(
                observationId,
                busSequence,
                SOURCE,
                statusPosition(busSequence),
                Optional.ofNullable(sourceTime),
                observedAt,
                ObservationCaptureMode.BOOTSTRAP,
                StatusSnapshotObservation.SCHEMA_VERSION,
                new StatusSnapshotObservation(
                        rawJson,
                        parsedJson(rawJson),
                        Optional.of(sourceTime),
                        OptionalLong.of(1L),
                        OptionalLong.of(0L),
                        OptionalInt.of(2)
                )
        );
    }

    private static RawJournalData rawJournal(String rawJson) {
        try {
            JsonNode rawObject = parsedJson(rawJson);
            String eventType = rawObject.path("event").textValue();
            return new RawJournalData(
                    rawJson,
                    rawObject,
                    Optional.ofNullable(eventType),
                    Optional.empty()
            );
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static SourcePosition journalPosition(long sequence) {
        return new TestSourcePosition("journal", sequence);
    }

    private static SourcePosition statusPosition(long sequence) {
        return new TestSourcePosition("status", sequence);
    }

    private static List<JsonNode> parseJsonLines(String corpusContent) {
        return Arrays.stream(corpusContent.split("\\R"))
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return JSON.readTree(line);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                })
                .toList();
    }

    private static JsonNode parsedJson(String rawJson) {
        try {
            return JSON.readTree(rawJson);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record FixtureJournalEvent(RawJournalData raw) implements JournalEventObservation {
        public FixtureJournalEvent {
            raw = Objects.requireNonNull(raw, "raw");
        }
    }

    private record TestSourcePosition(String kind, long sequence) implements SourcePosition {
    }
}
