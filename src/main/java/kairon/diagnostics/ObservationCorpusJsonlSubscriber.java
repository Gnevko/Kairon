package kairon.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.observation.ObservationPayload;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.status.StatusSnapshotObservation;

import java.io.IOException;
import java.io.Writer;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes selected observation payloads (journal + status) into a UTF-8 JSONL
 * corpus for later correlation and replay analysis.
 */
public final class ObservationCorpusJsonlSubscriber implements AutoCloseable {

    private static final String SCHEMA_VERSION = "kairon-observation-corpus-v1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Writer writer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object lock = new Object();

    public ObservationCorpusJsonlSubscriber(Path corpusFile) {
        Objects.requireNonNull(corpusFile, "corpusFile");
        Path absoluteCorpusFile = corpusFile.toAbsolutePath().normalize();
        Path parent = absoluteCorpusFile.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "FAILED_TO_CREATE_CORPUS_PARENT_DIRECTORY path=" + corpusFile,
                        failure
                );
            }
        }
        try {
            this.writer = Files.newBufferedWriter(
                    absoluteCorpusFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    ObservationCorpusJsonlSubscriber(Writer writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public void onNext(PublishedObservation<? extends ObservationPayload> observation) {
        Objects.requireNonNull(observation, "observation");
        synchronized (lock) {
            if (closed.get()) {
                throw new IllegalStateException("ObservationCorpusJsonlSubscriber is closed");
            }

            String eventType = null;
            String payloadType = observation.payload().getClass().getSimpleName();
            String rawJson = null;
            String observationKind;

            switch (observation.payload()) {
                case JournalEventObservation journal ->
                        {
                            observationKind = "JOURNAL";
                            eventType = journal.raw().optionalEventType().orElse(null);
                            rawJson = journal.raw().rawJson();
                        }
                case StatusSnapshotObservation ignored ->
                        {
                            observationKind = "STATUS";
                            eventType = "StatusSnapshot";
                            rawJson = ignored.rawJson();
                        }
                default -> {
                    return;
                }
            }

            writeLine(constructEnvelope(
                    observation,
                    observationKind,
                    payloadType,
                    eventType,
                    rawJson
            ));
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                writer.close();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }

    private void writeLine(Map<String, Object> envelope) {
        if (closed.get()) {
            throw new IllegalStateException("ObservationCorpusJsonlSubscriber is closed");
        }
        try {
            writer.write(JSON.writeValueAsString(envelope));
            writer.write('\n');
            writer.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Map<String, Object> constructEnvelope(
            PublishedObservation<? extends ObservationPayload> observation,
            String observationKind,
            String payloadType,
            String eventType,
            String rawJson
    ) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("observationId", observation.observationId());
        envelope.put("busSequence", observation.busSequence());
        envelope.put("source", observation.source().toString());
        envelope.put("sourcePosition", observation.sourcePosition().toString());
        envelope.put("sourceTime", toIsoString(observation.sourceTime()));
        envelope.put("observedAt", toIsoString(observation.observedAt()));
        envelope.put("captureMode", observation.captureMode().toString());
        envelope.put("payloadSchemaVersion", observation.schemaVersion());
        envelope.put("observationKind", observationKind);
        envelope.put("payloadType", payloadType);
        envelope.put("eventType", eventType);
        envelope.put("rawJson", rawJson);
        return envelope;
    }

    private static String toIsoString(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String toIsoString(Optional<Instant> sourceTime) {
        return sourceTime.map(Instant::toString).orElse(null);
    }
}
