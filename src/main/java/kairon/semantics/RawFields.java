package kairon.semantics;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * Typed reads over one raw journal object.
 *
 * <p>Adapters use this instead of parsing rendered prose. The node is read
 * once per adaptation, because
 * {@code RawJournalData.parsedJsonObject()} deep-copies on every call.</p>
 */
final class RawFields {

    private final JsonNode raw;

    private RawFields(JsonNode raw) {
        this.raw = Objects.requireNonNull(raw, "raw");
    }

    static RawFields of(JsonNode raw) {
        return new RawFields(raw);
    }

    /**
     * Prefers the game's localised rendering of {@code name}, falling back to
     * the raw token, and rejects opaque {@code $symbol;} identifiers.
     */
    SemanticValue displayText(String name) {
        Optional<String> localised = text(name + "_Localised");
        if (localised.isPresent()) {
            return SemanticValue.ofText(localised.orElseThrow());
        }
        return text(name)
                .filter(value -> !isOpaqueSymbol(value))
                .map(SemanticValue::ofText)
                .orElse(SemanticValue.unknown());
    }

    SemanticValue textValue(String name) {
        return text(name)
                .map(SemanticValue::ofText)
                .orElse(SemanticValue.unknown());
    }

    SemanticValue symbol(String name) {
        return text(name)
                .map(SemanticValue::ofSymbol)
                .orElse(SemanticValue.unknown());
    }

    SemanticValue booleanValue(String name) {
        JsonNode value = raw.get(name);
        return value != null && value.isBoolean()
                ? new SemanticValue.BooleanValue(value.booleanValue())
                : SemanticValue.unknown();
    }

    SemanticValue integral(String name) {
        JsonNode value = raw.get(name);
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToLong()
                ? new SemanticValue.IntegralValue(value.longValue())
                : SemanticValue.unknown();
    }

    SemanticValue decimal(String name) {
        JsonNode value = raw.get(name);
        if (value == null || !value.isNumber()) {
            return SemanticValue.unknown();
        }
        double number = value.doubleValue();
        return Double.isFinite(number)
                ? new SemanticValue.DecimalValue(number)
                : SemanticValue.unknown();
    }

    SemanticValue quantity(String name, String unit) {
        JsonNode value = raw.get(name);
        if (value == null || !value.isNumber()) {
            return SemanticValue.unknown();
        }
        double number = value.doubleValue();
        return Double.isFinite(number)
                ? new SemanticValue.QuantityValue(number, unit)
                : SemanticValue.unknown();
    }

    /**
     * Surface position, present only when both components are.
     *
     * <p>A half-known position is not a position and is never guessed.</p>
     */
    SemanticValue coordinates(String latitudeName, String longitudeName) {
        JsonNode latitude = raw.get(latitudeName);
        JsonNode longitude = raw.get(longitudeName);
        if (latitude == null || !latitude.isNumber()
                || longitude == null || !longitude.isNumber()) {
            return SemanticValue.unknown();
        }
        double latitudeValue = latitude.doubleValue();
        double longitudeValue = longitude.doubleValue();
        return Double.isFinite(latitudeValue)
                && Double.isFinite(longitudeValue)
                ? new SemanticValue.CoordinatesValue(
                        latitudeValue,
                        longitudeValue
                )
                : SemanticValue.unknown();
    }

    /** An identifier bound to an explicitly named kind. */
    SemanticValue identity(String kind, String name) {
        JsonNode value = raw.get(name);
        if (value != null
                && value.isIntegralNumber()
                && value.canConvertToLong()) {
            return new SemanticValue.IdentityValue(
                    kind,
                    Long.toString(value.longValue())
            );
        }
        return text(name)
                .map(token -> (SemanticValue)
                        new SemanticValue.IdentityValue(kind, token))
                .orElse(SemanticValue.unknown());
    }

    boolean flag(String name) {
        JsonNode value = raw.get(name);
        return value != null && value.isBoolean() && value.booleanValue();
    }

    boolean has(String name) {
        return raw.get(name) != null && !raw.get(name).isNull();
    }

    Optional<String> text(String name) {
        JsonNode value = raw.get(name);
        if (value == null
                || !value.isTextual()
                || value.textValue().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.textValue());
    }

    private static boolean isOpaqueSymbol(String value) {
        return value.startsWith("$") && value.endsWith(";");
    }
}
