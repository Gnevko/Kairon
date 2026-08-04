package kairon.observation.status;

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
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Strictly decodes one whole-file {@code Status.json} snapshot.
 */
public final class StatusSnapshotParser {

    private final ObjectMapper objectMapper;

    public StatusSnapshotParser() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        objectMapper = new ObjectMapper(factory);
    }

    public StatusParseResult parse(byte[] sourceBytes) {
        Objects.requireNonNull(sourceBytes, "sourceBytes");
        byte[] bytes = Arrays.copyOf(sourceBytes, sourceBytes.length);

        final String rawJson;
        try {
            rawJson = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            return failure(
                    StatusParseFailureKind.INVALID_UTF8,
                    "snapshot is not strict UTF-8"
            );
        }

        try (JsonParser parser = objectMapper.getFactory().createParser(rawJson)) {
            JsonNode object = objectMapper.readTree(parser);
            if (object == null) {
                return failure(
                        StatusParseFailureKind.MALFORMED_JSON,
                        "snapshot is empty"
                );
            }
            if (!object.isObject()) {
                return failure(
                        StatusParseFailureKind.NOT_AN_OBJECT,
                        "top-level JSON value is not an object"
                );
            }
            if (parser.nextToken() != null) {
                return failure(
                        StatusParseFailureKind.TRAILING_TOKEN,
                        "snapshot contains a trailing JSON token"
                );
            }

            JsonNode event = object.get("event");
            if (event == null
                    || !event.isTextual()
                    || !"Status".equals(event.textValue())) {
                return failure(
                        StatusParseFailureKind.WRONG_EVENT,
                        "event must be exactly Status"
                );
            }

            JsonNode timestampNode = object.get("timestamp");
            if (timestampNode == null
                    || !timestampNode.isTextual()
                    || timestampNode.textValue().isBlank()) {
                return failure(
                        StatusParseFailureKind.TIMESTAMP_MISSING,
                        "timestamp is required"
                );
            }
            final Instant timestamp;
            try {
                timestamp = Instant.parse(timestampNode.textValue());
            } catch (DateTimeParseException exception) {
                return failure(
                        StatusParseFailureKind.TIMESTAMP_INVALID,
                        "timestamp must be an ISO-8601 instant"
                );
            }

            ParsedOptionalLong flags = optionalNonNegativeLong(object, "Flags");
            if (!flags.valid()) {
                return failure(
                        StatusParseFailureKind.FLAGS_INVALID,
                        "Flags must be a nonnegative integer"
                );
            }
            ParsedOptionalLong flags2 =
                    optionalNonNegativeLong(object, "Flags2");
            if (!flags2.valid()) {
                return failure(
                        StatusParseFailureKind.FLAGS2_INVALID,
                        "Flags2 must be a nonnegative integer"
                );
            }
            ParsedOptionalInt guiFocus =
                    optionalNonNegativeInt(object, "GuiFocus");
            if (!guiFocus.valid()) {
                return failure(
                        StatusParseFailureKind.GUI_FOCUS_INVALID,
                        "GuiFocus must be a nonnegative integer"
                );
            }

            return new ParsedStatusSnapshot(new StatusSnapshotObservation(
                    rawJson,
                    object,
                    Optional.of(timestamp),
                    flags.value(),
                    flags2.value(),
                    guiFocus.value()
            ));
        } catch (IOException | RuntimeException exception) {
            return failure(
                    StatusParseFailureKind.MALFORMED_JSON,
                    "snapshot is not one valid JSON object"
            );
        }
    }

    private static ParsedOptionalLong optionalNonNegativeLong(
            JsonNode object,
            String fieldName
    ) {
        JsonNode value = object.get(fieldName);
        if (value == null) {
            return new ParsedOptionalLong(true, OptionalLong.empty());
        }
        if (!value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < 0) {
            return new ParsedOptionalLong(false, OptionalLong.empty());
        }
        return new ParsedOptionalLong(
                true,
                OptionalLong.of(value.longValue())
        );
    }

    private static ParsedOptionalInt optionalNonNegativeInt(
            JsonNode object,
            String fieldName
    ) {
        JsonNode value = object.get(fieldName);
        if (value == null) {
            return new ParsedOptionalInt(true, OptionalInt.empty());
        }
        if (!value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.intValue() < 0) {
            return new ParsedOptionalInt(false, OptionalInt.empty());
        }
        return new ParsedOptionalInt(
                true,
                OptionalInt.of(value.intValue())
        );
    }

    private static StatusParseFailure failure(
            StatusParseFailureKind kind,
            String diagnostic
    ) {
        return new StatusParseFailure(kind, diagnostic);
    }

    public sealed interface StatusParseResult
            permits ParsedStatusSnapshot, StatusParseFailure {
    }

    public record ParsedStatusSnapshot(
            StatusSnapshotObservation observation
    ) implements StatusParseResult {

        public ParsedStatusSnapshot {
            Objects.requireNonNull(observation, "observation");
        }
    }

    public record StatusParseFailure(
            StatusParseFailureKind kind,
            String diagnostic
    ) implements StatusParseResult {

        public StatusParseFailure {
            Objects.requireNonNull(kind, "kind");
            diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        }
    }

    public enum StatusParseFailureKind {
        INVALID_UTF8,
        MALFORMED_JSON,
        NOT_AN_OBJECT,
        TRAILING_TOKEN,
        WRONG_EVENT,
        TIMESTAMP_MISSING,
        TIMESTAMP_INVALID,
        FLAGS_INVALID,
        FLAGS2_INVALID,
        GUI_FOCUS_INVALID
    }

    private record ParsedOptionalLong(
            boolean valid,
            OptionalLong value
    ) {
    }

    private record ParsedOptionalInt(
            boolean valid,
            OptionalInt value
    ) {
    }
}
