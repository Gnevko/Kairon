package kairon.behavior.normalize;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Compact deterministic representation of one graph-significant journal event.
 */
public record NormalizedBehaviorEvent(
        NormalizedEventType eventType,
        Instant timestamp,
        Map<String, JsonNode> attributes,
        String originalEventName
) {

    public NormalizedBehaviorEvent {
        eventType = Objects.requireNonNull(eventType, "eventType");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        attributes = immutableJsonMap(attributes);
        originalEventName = requireNonBlank(
                originalEventName,
                "originalEventName"
        );
    }

    @Override
    public Map<String, JsonNode> attributes() {
        return immutableJsonMap(attributes);
    }

    private static Map<String, JsonNode> immutableJsonMap(
            Map<String, JsonNode> source
    ) {
        Objects.requireNonNull(source, "attributes");
        Map<String, JsonNode> sorted = new TreeMap<>();
        source.forEach((name, value) -> {
            String checkedName = requireNonBlank(name, "attribute name");
            JsonNode checkedValue = Objects.requireNonNull(
                    value,
                    "attribute value"
            );
            sorted.put(checkedName, checkedValue.deepCopy());
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
