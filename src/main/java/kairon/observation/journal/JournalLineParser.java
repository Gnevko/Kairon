package kairon.observation.journal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Strictly decodes and validates one complete journal record.
 */
public final class JournalLineParser {

    private final ObjectMapper objectMapper;

    public JournalLineParser() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.objectMapper = new ObjectMapper(factory);
    }

    public JournalParseResult parse(CompleteJournalRecord record) {
        Objects.requireNonNull(record, "record");

        final String rawJson;
        try {
            rawJson = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(record.bytes()))
                    .toString();
        } catch (CharacterCodingException exception) {
            return new JournalParseFailure(
                    record.journalBasename(),
                    record.zeroBasedSourceByteOffset(),
                    JournalParseFailureKind.INVALID_UTF8,
                    "record is not strict UTF-8"
            );
        }

        try (JsonParser parser = objectMapper.getFactory().createParser(rawJson)) {
            JsonNode node = objectMapper.readTree(parser);
            if (node == null) {
                return failure(record, JournalParseFailureKind.MALFORMED_JSON, "record is empty");
            }
            if (!node.isObject()) {
                return failure(
                        record,
                        JournalParseFailureKind.NOT_AN_OBJECT,
                        "top-level JSON value is not an object"
                );
            }
            if (parser.nextToken() != null) {
                return failure(
                        record,
                        JournalParseFailureKind.TRAILING_TOKEN,
                        "record contains a trailing JSON token"
                );
            }

            Optional<String> eventType = Optional.ofNullable(node.get("event"))
                    .filter(JsonNode::isTextual)
                    .map(JsonNode::textValue);
            Optional<Instant> timestamp = Optional.ofNullable(node.get("timestamp"))
                    .filter(JsonNode::isTextual)
                    .flatMap(JournalLineParser::parseInstant);

            return new ParsedJournalRecord(
                    record.journalBasename(),
                    record.zeroBasedSourceByteOffset(),
                    rawJson,
                    node,
                    eventType,
                    timestamp
            );
        } catch (IOException | RuntimeException exception) {
            return failure(
                    record,
                    JournalParseFailureKind.MALFORMED_JSON,
                    "record is not one valid JSON object"
            );
        }
    }

    private static JournalParseFailure failure(
            CompleteJournalRecord record,
            JournalParseFailureKind kind,
            String message
    ) {
        return new JournalParseFailure(
                record.journalBasename(),
                record.zeroBasedSourceByteOffset(),
                kind,
                message
        );
    }

    private static Optional<Instant> parseInstant(JsonNode timestamp) {
        try {
            return Optional.of(Instant.parse(timestamp.textValue()));
        } catch (DateTimeParseException exception) {
            return Optional.empty();
        }
    }

    public sealed interface JournalParseResult permits ParsedJournalRecord, JournalParseFailure {
    }

    public record CompleteJournalRecord(
            String journalBasename,
            long zeroBasedSourceByteOffset,
            byte[] bytes
    ) {

        public CompleteJournalRecord {
            journalBasename = Objects.requireNonNull(journalBasename, "journalBasename");
            if (journalBasename.isBlank()) {
                throw new IllegalArgumentException("journalBasename must not be blank");
            }
            if (zeroBasedSourceByteOffset < 0) {
                throw new IllegalArgumentException("zeroBasedSourceByteOffset must be nonnegative");
            }
            bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }
    }

    public record ParsedJournalRecord(
            String journalBasename,
            long zeroBasedSourceByteOffset,
            String rawJson,
            JsonNode parsedJsonObject,
            Optional<String> optionalEventType,
            Optional<Instant> optionalJournalTimestamp
    ) implements JournalParseResult {

        public ParsedJournalRecord {
            journalBasename = Objects.requireNonNull(journalBasename, "journalBasename");
            rawJson = Objects.requireNonNull(rawJson, "rawJson");
            parsedJsonObject =
                    Objects.requireNonNull(parsedJsonObject, "parsedJsonObject").deepCopy();
            if (!parsedJsonObject.isObject()) {
                throw new IllegalArgumentException("parsedJsonObject must be a JSON object");
            }
            optionalEventType = Objects.requireNonNull(optionalEventType, "optionalEventType");
            optionalJournalTimestamp =
                    Objects.requireNonNull(optionalJournalTimestamp, "optionalJournalTimestamp");
            if (zeroBasedSourceByteOffset < 0) {
                throw new IllegalArgumentException("zeroBasedSourceByteOffset must be nonnegative");
            }
        }

        @Override
        public JsonNode parsedJsonObject() {
            return parsedJsonObject.deepCopy();
        }
    }

    public record JournalParseFailure(
            String journalBasename,
            long zeroBasedSourceByteOffset,
            JournalParseFailureKind kind,
            String diagnostic
    ) implements JournalParseResult {

        public JournalParseFailure {
            journalBasename = Objects.requireNonNull(journalBasename, "journalBasename");
            kind = Objects.requireNonNull(kind, "kind");
            diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
            if (zeroBasedSourceByteOffset < 0) {
                throw new IllegalArgumentException("zeroBasedSourceByteOffset must be nonnegative");
            }
        }
    }

    public enum JournalParseFailureKind {
        INVALID_UTF8,
        MALFORMED_JSON,
        NOT_AN_OBJECT,
        TRAILING_TOKEN
    }
}
