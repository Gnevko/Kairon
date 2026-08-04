package kairon.behavior.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import kairon.behavior.normalize.NormalizedEventType;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One exact significant external observation in a concrete system visit.
 *
 * <p>{@code source} is provenance recorded at acceptance. It is deliberately
 * <strong>not persisted</strong>: the graph store schema is unchanged, so an
 * occurrence restored from disk carries {@code null} there. {@code null}
 * therefore means "this occurrence predates in-process provenance", and is
 * never a claim about what produced it. Persisting it is an accepted, deferred
 * limitation; see {@code docs/CURRENT_STATE.md}.</p>
 */
public record EventOccurrence(
        EventOccurrenceId id,
        GraphId graphId,
        SystemEpisodeId episodeId,
        long episodeSequence,
        NormalizedEventType eventType,
        String originalEventName,
        @JsonIgnore EventOccurrenceSource source,
        Instant timestamp,
        long sourceSequence,
        String sourceId,
        Map<String, JsonNode> attributes,
        ContextSnapshot context
) {

    /**
     * Exact single-writer order inside one system episode.
     *
     * <p>Source-local sequences from independently updated files cannot be
     * compared with each other. The episode sequence therefore preserves the
     * order in which the graph projection accepted journal and status facts,
     * while {@link #sourceSequence()} remains source identity metadata.</p>
     */
    public static final Comparator<EventOccurrence> EPISODE_ORDER =
            Comparator.comparingLong(EventOccurrence::episodeSequence)
                    .thenComparing(EventOccurrence::timestamp)
                    .thenComparingLong(EventOccurrence::sourceSequence)
                    .thenComparing(EventOccurrence::sourceId)
                    .thenComparing(EventOccurrence::id);

    public static final Comparator<EventOccurrence> CHRONOLOGICAL_ORDER =
            Comparator.comparing(EventOccurrence::timestamp)
                    .thenComparingLong(EventOccurrence::sourceSequence)
                    .thenComparing(EventOccurrence::sourceId)
                    .thenComparing(EventOccurrence::id);

    public EventOccurrence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(episodeId, "episodeId");
        if (episodeSequence < 0) {
            throw new IllegalArgumentException(
                    "episodeSequence must be nonnegative"
            );
        }
        Objects.requireNonNull(eventType, "eventType");
        originalEventName = requireNonBlank(
                originalEventName,
                "originalEventName"
        );
        Objects.requireNonNull(timestamp, "timestamp");
        if (sourceSequence < 0) {
            throw new IllegalArgumentException(
                    "sourceSequence must be nonnegative"
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
        source.forEach((key, value) -> {
            String validatedKey = requireNonBlank(key, "attribute key");
            copy.put(
                    validatedKey,
                    Objects.requireNonNull(value, "attribute value").deepCopy()
            );
        });
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
