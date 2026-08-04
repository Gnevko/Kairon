package kairon.observation.status;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.ObservationPayload;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * One exact, immutable snapshot read from Elite Dangerous {@code Status.json}.
 *
 * <p>The raw source object is preserved for diagnostics and future consumers.
 * The optional technical fields are extracted once by the strict parser, but
 * they do not contain subscriber-owned state or inferred transitions.</p>
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 14</a>
 */
public record StatusSnapshotObservation(
        String rawJson,
        JsonNode parsedJsonObject,
        Optional<Instant> optionalTimestamp,
        OptionalLong optionalFlags,
        OptionalLong optionalFlags2,
        OptionalInt optionalGuiFocus
) implements ObservationPayload {

    public static final String SCHEMA_VERSION =
            "kairon.status-snapshot-observation/v1";

    public StatusSnapshotObservation {
        rawJson = Objects.requireNonNull(rawJson, "rawJson");
        if (rawJson.isBlank()) {
            throw new IllegalArgumentException("rawJson must not be blank");
        }
        parsedJsonObject = Objects.requireNonNull(
                parsedJsonObject,
                "parsedJsonObject"
        ).deepCopy();
        if (!parsedJsonObject.isObject()) {
            throw new IllegalArgumentException(
                    "parsedJsonObject must be a JSON object"
            );
        }
        optionalTimestamp = Objects.requireNonNull(
                optionalTimestamp,
                "optionalTimestamp"
        );
        optionalFlags = Objects.requireNonNull(optionalFlags, "optionalFlags");
        optionalFlags2 = Objects.requireNonNull(
                optionalFlags2,
                "optionalFlags2"
        );
        optionalGuiFocus = Objects.requireNonNull(
                optionalGuiFocus,
                "optionalGuiFocus"
        );
        if (optionalFlags.isPresent() && optionalFlags.getAsLong() < 0) {
            throw new IllegalArgumentException(
                    "optionalFlags must be nonnegative"
            );
        }
        if (optionalFlags2.isPresent() && optionalFlags2.getAsLong() < 0) {
            throw new IllegalArgumentException(
                    "optionalFlags2 must be nonnegative"
            );
        }
        if (optionalGuiFocus.isPresent()
                && optionalGuiFocus.getAsInt() < 0) {
            throw new IllegalArgumentException(
                    "optionalGuiFocus must be nonnegative"
            );
        }
    }

    @Override
    public JsonNode parsedJsonObject() {
        return parsedJsonObject.deepCopy();
    }

    /**
     * Explicit source-specific alias used by consumers that combine several
     * observation payload types with differently named source timestamps.
     */
    public Optional<Instant> optionalStatusTimestamp() {
        return optionalTimestamp;
    }
}
