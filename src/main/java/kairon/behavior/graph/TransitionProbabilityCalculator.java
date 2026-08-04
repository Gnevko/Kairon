package kairon.behavior.graph;

import kairon.behavior.context.TransitionContextKeyFactory;
import kairon.behavior.model.ContextKey;
import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.NextEventPrediction;
import kairon.behavior.model.PredictionBasis;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.TransitionEdge;
import kairon.behavior.model.TransitionEdgeView;
import kairon.behavior.model.WeightedCounter;
import kairon.behavior.normalize.NormalizedEventType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure probability calculations over already aggregated per-ship edges.
 */
public final class TransitionProbabilityCalculator {

    private final Duration halfLife;
    private final double contextPriorStrength;
    private final TransitionContextKeyFactory contextKeyFactory;

    public TransitionProbabilityCalculator(
            Duration halfLife,
            double contextPriorStrength,
            TransitionContextKeyFactory contextKeyFactory
    ) {
        this.halfLife = requirePositive(halfLife, "halfLife");
        if (!Double.isFinite(contextPriorStrength)
                || contextPriorStrength <= 0.0) {
            throw new IllegalArgumentException(
                    "contextPriorStrength must be finite and positive"
            );
        }
        this.contextPriorStrength = contextPriorStrength;
        this.contextKeyFactory = Objects.requireNonNull(
                contextKeyFactory,
                "contextKeyFactory"
        );
    }

    public List<TransitionEdgeView> outgoingEdges(
            ShipBehaviorGraph graph,
            NormalizedEventType fromEventType,
            Instant evaluationTime
    ) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(fromEventType, "fromEventType");
        Objects.requireNonNull(evaluationTime, "evaluationTime");
        List<EdgeWeight> outgoing = globalWeights(
                graph,
                fromEventType,
                evaluationTime
        );
        double total = outgoing.stream()
                .mapToDouble(EdgeWeight::weight)
                .sum();
        if (total <= 0.0) {
            return List.of();
        }
        return outgoing.stream()
                .map(weight -> new TransitionEdgeView(
                        weight.edge().key().fromEventType(),
                        weight.edge().key().toEventType(),
                        weight.edge().globalCounter().rawCount(),
                        weight.weight(),
                        weight.weight() / total,
                        weight.edge().firstSeenAt(),
                        weight.edge().lastSeenAt()
                ))
                .sorted(Comparator
                        .comparingDouble(TransitionEdgeView::globalProbability)
                        .reversed()
                        .thenComparing(TransitionEdgeView::toEventType))
                .toList();
    }

    public List<NextEventPrediction> predict(
            ShipBehaviorGraph graph,
            GraphCursor cursor,
            ContextSnapshot currentContext,
            Instant evaluationTime,
            int limit
    ) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(currentContext, "currentContext");
        Objects.requireNonNull(evaluationTime, "evaluationTime");
        if (!cursor.graphId().equals(graph.graphId())) {
            throw new IllegalArgumentException(
                    "cursor belongs to another graph"
            );
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }

        List<EdgeWeight> outgoing = globalWeights(
                graph,
                cursor.eventType(),
                evaluationTime
        );
        double globalTotal = outgoing.stream()
                .mapToDouble(EdgeWeight::weight)
                .sum();
        if (globalTotal <= 0.0) {
            return List.of();
        }

        ContextKey contextKey = contextKeyFactory.create(
                cursor.eventType(),
                currentContext
        );
        List<ContextualWeight> weights = new ArrayList<>();
        double contextSupport = 0.0;
        for (EdgeWeight global : outgoing) {
            double contextWeight = global.edge()
                    .contextCounter(contextKey)
                    .map(counter -> counter.valueAt(evaluationTime, halfLife))
                    .orElse(0.0);
            // The factual all-time count inside this bucket. Read only; no
            // weight, probability or basis depends on it.
            long contextRawCount = global.edge()
                    .contextCounter(contextKey)
                    .map(WeightedCounter::rawCount)
                    .orElse(0L);
            contextSupport += contextWeight;
            weights.add(new ContextualWeight(
                    global.edge(),
                    global.weight(),
                    global.weight() / globalTotal,
                    contextWeight,
                    contextRawCount
            ));
        }

        boolean contextual = contextSupport > 0.0;
        double scoreTotal = contextual
                ? contextSupport + contextPriorStrength
                : globalTotal;
        double finalContextSupport = contextSupport;
        List<NextEventPrediction> result = weights.stream()
                .map(weight -> {
                    double effectiveWeight = contextual
                            ? weight.contextWeight()
                                    + contextPriorStrength
                                    * weight.globalProbability()
                            : weight.globalWeight();
                    return new NextEventPrediction(
                            graph.graphId(),
                            cursor.episodeId(),
                            cursor.occurrenceId(),
                            cursor.eventType(),
                            weight.edge().key().toEventType(),
                            effectiveWeight / scoreTotal,
                            weight.globalProbability(),
                            effectiveWeight,
                            weight.edge().globalCounter().rawCount(),
                            weight.contextRawCount(),
                            contextKey,
                            finalContextSupport,
                            contextual
                                    ? PredictionBasis.CONTEXTUAL
                                    : PredictionBasis.GLOBAL,
                            weight.edge().lastSeenAt()
                    );
                })
                .sorted(Comparator
                        .comparingDouble(NextEventPrediction::probability)
                        .reversed()
                        .thenComparing(
                                NextEventPrediction::predictedEventType
                        ))
                .limit(limit)
                .toList();
        return List.copyOf(result);
    }

    private List<EdgeWeight> globalWeights(
            ShipBehaviorGraph graph,
            NormalizedEventType fromEventType,
            Instant evaluationTime
    ) {
        return graph.edges().stream()
                .filter(edge -> edge.key()
                        .fromEventType()
                        .equals(fromEventType))
                .map(edge -> new EdgeWeight(
                        edge,
                        edge.globalCounter().valueAt(
                                evaluationTime,
                                halfLife
                        )
                ))
                .filter(weight -> weight.weight() > 0.0)
                .toList();
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private record EdgeWeight(TransitionEdge edge, double weight) {
    }

    private record ContextualWeight(
            TransitionEdge edge,
            double globalWeight,
            double globalProbability,
            double contextWeight,
            long contextRawCount
    ) {
    }
}
