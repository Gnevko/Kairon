package kairon.behavior.graph;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable read model for the selected concrete occurrence.
 */
public record EventOccurrenceDetailsSnapshot(
        GraphId graphId,
        SystemEpisodeId episodeId,
        EventOccurrenceId occurrenceId,
        NormalizedEventType eventType,
        String originalEventName,
        Instant timestamp,
        long episodeSequence,
        long sourceSequence,
        String sourceId,
        Map<String, JsonNode> attributes,
        ContextSnapshot context
) {

    public EventOccurrenceDetailsSnapshot {
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(episodeId, "episodeId");
        Objects.requireNonNull(occurrenceId, "occurrenceId");
        Objects.requireNonNull(eventType, "eventType");
        originalEventName = requireNonBlank(
                originalEventName,
                "originalEventName"
        );
        Objects.requireNonNull(timestamp, "timestamp");
        if (episodeSequence < 0 || sourceSequence < 0) {
            throw new IllegalArgumentException(
                    "sequences must be nonnegative"
            );
        }
        sourceId = requireNonBlank(sourceId, "sourceId");
        attributes = immutableJsonMap(attributes);
        Objects.requireNonNull(context, "context");
    }

    @Override
    public Map<String, JsonNode> attributes() {
        return immutableJsonMap(attributes);
    }

    private static Map<String, JsonNode> immutableJsonMap(
            Map<String, JsonNode> source
    ) {
        Objects.requireNonNull(source, "attributes");
        TreeMap<String, JsonNode> copy = new TreeMap<>();
        source.forEach((key, value) -> copy.put(
                requireNonBlank(key, "attribute key"),
                Objects.requireNonNull(value, "attribute value").deepCopy()
        ));
        return Collections.unmodifiableMap(copy);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
