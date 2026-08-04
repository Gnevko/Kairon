package kairon.behavior.export;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import kairon.behavior.model.ContextKey;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.EventTypeNode;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.OccurrenceTransition;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.TransitionEdge;
import kairon.behavior.normalize.NormalizedEventType;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministic, human-readable graph and episode diagnostics.
 */
public final class BehaviorGraphExporter {

    public static final String GRAPH_EXPORT_SCHEMA_VERSION =
            "kairon.behavior-graph-export/v1";
    public static final String EPISODE_EXPORT_SCHEMA_VERSION =
            "kairon.system-episode-export/v1";

    private final ObjectWriter writer;

    public BehaviorGraphExporter() {
        this(deterministicMapper());
    }

    BehaviorGraphExporter(ObjectMapper mapper) {
        this.writer = stableWriter(
                Objects.requireNonNull(mapper, "mapper")
        );
    }

    public String exportGraph(
            ShipBehaviorGraph graph,
            Instant evaluationTime,
            Duration halfLife
    ) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(evaluationTime, "evaluationTime");
        requirePositive(halfLife);

        Map<NormalizedEventType, Double> outgoingTotals = new TreeMap<>();
        for (TransitionEdge edge : graph.edges()) {
            outgoingTotals.merge(
                    edge.key().fromEventType(),
                    edge.globalCounter().valueAt(evaluationTime, halfLife),
                    Double::sum
            );
        }

        List<GraphNodeExport> nodes = graph.nodes().stream()
                .map(BehaviorGraphExporter::exportNode)
                .toList();
        List<GraphEdgeExport> edges = new ArrayList<>(graph.edges().size());
        for (TransitionEdge edge : graph.edges()) {
            double effectiveWeight = edge.globalCounter()
                    .valueAt(evaluationTime, halfLife);
            double total = outgoingTotals.getOrDefault(
                    edge.key().fromEventType(),
                    0.0
            );
            double probability = total == 0.0
                    ? 0.0
                    : effectiveWeight / total;
            List<ContextCounterExport> contexts = edge.contextCounters()
                    .stream()
                    .map(context -> new ContextCounterExport(
                            context.key().canonical(),
                            context.counter().rawCount(),
                            context.counter().valueAt(
                                    evaluationTime,
                                    halfLife
                            )
                    ))
                    .toList();
            edges.add(new GraphEdgeExport(
                    edge.key().fromEventType(),
                    edge.key().toEventType(),
                    edge.globalCounter().rawCount(),
                    effectiveWeight,
                    probability,
                    edge.firstSeenAt(),
                    edge.lastSeenAt(),
                    contexts
            ));
        }

        GraphExport export = new GraphExport(
                GRAPH_EXPORT_SCHEMA_VERSION,
                graph.graphId(),
                exportCursor(graph.cursor()),
                evaluationTime,
                nodes,
                List.copyOf(edges)
        );
        return write(export);
    }

    public String exportEpisode(SystemEpisode episode) {
        Objects.requireNonNull(episode, "episode");
        EpisodeExport export = new EpisodeExport(
                EPISODE_EXPORT_SCHEMA_VERSION,
                episode.id().value(),
                episode.graphId(),
                episode.systemAddress(),
                episode.systemName(),
                episode.startedAt(),
                episode.completedAt(),
                episode.entrySource().name(),
                episode.completionReason() == null
                        ? null
                        : episode.completionReason().name(),
                episode.rootOccurrenceId(),
                episode.timeline(),
                episode.occurrenceTransitions()
        );
        return write(export);
    }

    public String exportDot(
            ShipBehaviorGraph graph,
            Instant evaluationTime,
            Duration halfLife
    ) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(evaluationTime, "evaluationTime");
        requirePositive(halfLife);

        Map<NormalizedEventType, Double> outgoingTotals = new TreeMap<>();
        for (TransitionEdge edge : graph.edges()) {
            outgoingTotals.merge(
                    edge.key().fromEventType(),
                    edge.globalCounter().valueAt(evaluationTime, halfLife),
                    Double::sum
            );
        }

        StringBuilder dot = new StringBuilder();
        dot.append("digraph BehaviorGraph {\n")
                .append("  rankdir=LR;\n")
                .append("  graph [label=\"")
                .append(escapeDot(graph.graphId().canonicalValue()))
                .append("\", labelloc=t];\n")
                .append("  node [shape=box];\n");

        for (EventTypeNode node : graph.nodes()) {
            String eventType = node.eventType().value();
            dot.append("  \"")
                    .append(escapeDot(eventType))
                    .append("\" [label=\"")
                    .append(escapeDot(eventType))
                    .append("\\noccurrences=")
                    .append(node.rawOccurrenceCount())
                    .append("\"];\n");
        }
        for (TransitionEdge edge : graph.edges()) {
            double effectiveWeight = edge.globalCounter()
                    .valueAt(evaluationTime, halfLife);
            double total = outgoingTotals.getOrDefault(
                    edge.key().fromEventType(),
                    0.0
            );
            double probability = total == 0.0
                    ? 0.0
                    : effectiveWeight / total;
            dot.append("  \"")
                    .append(escapeDot(edge.key().fromEventType().value()))
                    .append("\" -> \"")
                    .append(escapeDot(edge.key().toEventType().value()))
                    .append("\" [label=\"raw=")
                    .append(edge.globalCounter().rawCount())
                    .append(", weight=")
                    .append(format(effectiveWeight))
                    .append(", p=")
                    .append(format(probability))
                    .append("\"];\n");
        }
        return dot.append("}\n").toString();
    }

    private static GraphNodeExport exportNode(EventTypeNode node) {
        return new GraphNodeExport(
                node.eventType(),
                node.rawOccurrenceCount(),
                node.firstSeenAt(),
                node.lastSeenAt()
        );
    }

    private static CursorExport exportCursor(GraphCursor cursor) {
        if (cursor == null) {
            return null;
        }
        return new CursorExport(
                cursor.episodeId().value(),
                cursor.occurrenceId(),
                cursor.eventType(),
                cursor.updatedAt()
        );
    }

    private String write(Object value) {
        try {
            return writer.writeValueAsString(value) + '\n';
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot serialize behavior graph export",
                    failure
            );
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String escapeDot(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static void requirePositive(Duration halfLife) {
        if (halfLife == null
                || halfLife.isZero()
                || halfLife.isNegative()) {
            throw new IllegalArgumentException("halfLife must be positive");
        }
    }

    private static ObjectMapper deterministicMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return JsonMapper.builder(factory)
                .addModule(new JavaTimeModule())
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    private static ObjectWriter stableWriter(ObjectMapper mapper) {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return mapper.writer(printer);
    }

    private record GraphExport(
            String schemaVersion,
            GraphId graphId,
            CursorExport cursor,
            Instant evaluationTime,
            List<GraphNodeExport> nodes,
            List<GraphEdgeExport> edges
    ) {
    }

    private record CursorExport(
            String episodeId,
            EventOccurrenceId occurrenceId,
            NormalizedEventType eventType,
            Instant updatedAt
    ) {
    }

    private record GraphNodeExport(
            NormalizedEventType eventType,
            long rawOccurrenceCount,
            Instant firstSeenAt,
            Instant lastSeenAt
    ) {
    }

    private record GraphEdgeExport(
            NormalizedEventType from,
            NormalizedEventType to,
            long rawCount,
            double effectiveWeight,
            double globalProbability,
            Instant firstSeenAt,
            Instant lastSeenAt,
            List<ContextCounterExport> contexts
    ) {
    }

    private record ContextCounterExport(
            String key,
            long rawCount,
            double effectiveWeight
    ) {
    }

    private record EpisodeExport(
            String schemaVersion,
            String episodeId,
            GraphId graphId,
            long systemAddress,
            String systemName,
            Instant startedAt,
            Instant completedAt,
            String entrySource,
            String completionReason,
            EventOccurrenceId rootOccurrenceId,
            List<EventOccurrence> timeline,
            List<OccurrenceTransition> transitions
    ) {
    }
}
