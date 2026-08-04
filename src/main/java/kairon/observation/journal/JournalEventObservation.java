package kairon.observation.journal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.observation.ObservationPayload;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Common contract for one complete, validated Elite Dangerous journal event.
 *
 * <p>Concrete event records live in {@code kairon.observation.journal.event}
 * subpackages. Every record keeps exact raw journal data and contains no narrative
 * summary, importance score, or subscriber processing state.</p>
 */
public interface JournalEventObservation extends ObservationPayload {

    String SCHEMA_VERSION = "kairon.journal-event-observation/v1";

    RawJournalData raw();

    /**
     * Verifies the discriminator invariant used by every concrete event record.
     */
    public static RawJournalData requireEvent(
            RawJournalData raw,
            String expectedEventType
    ) {
        raw = Objects.requireNonNull(raw, "raw");
        expectedEventType = Objects.requireNonNull(
                expectedEventType,
                "expectedEventType"
        );
        String actualEventType = raw.optionalEventType().orElse(null);
        if (!expectedEventType.equals(actualEventType)) {
            throw new IllegalArgumentException(
                    "Expected journal event discriminator '" + expectedEventType
                            + "' but received '" + actualEventType + "'"
            );
        }
        return raw;
    }

    /**
     * Exact source data shared by every event-specific payload.
     *
     * <p>Jackson nodes are mutable, so the constructor stores and the accessor returns
     * defensive copies.</p>
     */
    record RawJournalData(
            String rawJson,
            JsonNode parsedJsonObject,
            Optional<String> optionalEventType,
            Optional<Instant> optionalJournalTimestamp
    ) {

        public RawJournalData {
            rawJson = Objects.requireNonNull(rawJson, "rawJson");
            parsedJsonObject =
                    Objects.requireNonNull(
                            parsedJsonObject,
                            "parsedJsonObject"
                    ).deepCopy();
            if (!parsedJsonObject.isObject()) {
                throw new IllegalArgumentException(
                        "parsedJsonObject must be a JSON object"
                );
            }
            optionalEventType =
                    Objects.requireNonNull(
                            optionalEventType,
                            "optionalEventType"
                    );
            optionalJournalTimestamp =
                    Objects.requireNonNull(
                            optionalJournalTimestamp,
                            "optionalJournalTimestamp"
                    );
            Optional<String> parsedEventType =
                    Optional.ofNullable(parsedJsonObject.get("event"))
                            .filter(JsonNode::isTextual)
                            .map(JsonNode::textValue);
            if (!parsedEventType.equals(optionalEventType)) {
                throw new IllegalArgumentException(
                        "optionalEventType must match parsedJsonObject.event"
                );
            }
            JournalRawDataVerifier.requireExactParsedValue(
                    rawJson,
                    parsedJsonObject
            );
        }

        @Override
        public JsonNode parsedJsonObject() {
            return parsedJsonObject.deepCopy();
        }
    }
}

/**
 * Enforces consistency for the public raw payload constructor without
 * normalizing or replacing the authoritative source JSON.
 */
final class JournalRawDataVerifier {

    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build()
    );

    private JournalRawDataVerifier() {
    }

    static void requireExactParsedValue(String rawJson, JsonNode expected) {
        final JsonNode parsed;
        try (JsonParser parser = JSON.getFactory().createParser(rawJson)) {
            parsed = JSON.readTree(parser);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException(
                        "rawJson must contain exactly one JSON value"
                );
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "rawJson must contain one valid JSON object",
                    exception
            );
        }
        if (parsed == null || !parsed.isObject() || !parsed.equals(expected)) {
            throw new IllegalArgumentException(
                    "rawJson must match parsedJsonObject exactly"
            );
        }
    }
}
