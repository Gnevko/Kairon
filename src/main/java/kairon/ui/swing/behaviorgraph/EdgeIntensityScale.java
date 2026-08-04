package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationEdge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Logarithmic, monotonic mapping from effective edge weight to contrast.
 */
public final class EdgeIntensityScale {

    public static final double DEFAULT_MINIMUM_INTENSITY = 0.20;
    public static final double DEFAULT_MAXIMUM_INTENSITY = 1.0;

    private final double minimumIntensity;
    private final double maximumIntensity;

    public EdgeIntensityScale() {
        this(
                DEFAULT_MINIMUM_INTENSITY,
                DEFAULT_MAXIMUM_INTENSITY
        );
    }

    public EdgeIntensityScale(
            double minimumIntensity,
            double maximumIntensity
    ) {
        if (!Double.isFinite(minimumIntensity)
                || !Double.isFinite(maximumIntensity)
                || minimumIntensity < 0.0
                || maximumIntensity > 1.0
                || minimumIntensity > maximumIntensity) {
            throw new IllegalArgumentException(
                    "intensity range must be finite and inside [0, 1]"
            );
        }
        this.minimumIntensity = minimumIntensity;
        this.maximumIntensity = maximumIntensity;
    }

    public double minimumIntensity() {
        return minimumIntensity;
    }

    public double maximumIntensity() {
        return maximumIntensity;
    }

    public Map<VisualizationEdge, Double> scale(
            List<VisualizationEdge> edges
    ) {
        Objects.requireNonNull(edges, "edges");
        double maxWeight = maximumPositiveWeight(edges);
        LinkedHashMap<VisualizationEdge, Double> result =
                new LinkedHashMap<>();
        for (VisualizationEdge edge : edges) {
            VisualizationEdge required = Objects.requireNonNull(
                    edge,
                    "edge"
            );
            result.put(
                    required,
                    intensity(required.effectiveWeight(), maxWeight)
            );
        }
        return Collections.unmodifiableMap(result);
    }

    public double intensity(double edgeWeight, double maxWeight) {
        if (!(edgeWeight > 0.0) || !(maxWeight > 0.0)) {
            return minimumIntensity;
        }
        if (Double.isInfinite(maxWeight)) {
            return Double.isInfinite(edgeWeight)
                    ? maximumIntensity
                    : minimumIntensity;
        }
        if (!Double.isFinite(edgeWeight)) {
            return edgeWeight == Double.POSITIVE_INFINITY
                    ? maximumIntensity
                    : minimumIntensity;
        }

        double denominator = Math.log1p(maxWeight);
        if (!(denominator > 0.0) || !Double.isFinite(denominator)) {
            return minimumIntensity;
        }
        double normalized = Math.log1p(edgeWeight) / denominator;
        normalized = Math.max(0.0, Math.min(1.0, normalized));
        return minimumIntensity
                + normalized
                * (maximumIntensity - minimumIntensity);
    }

    private static double maximumPositiveWeight(
            List<VisualizationEdge> edges
    ) {
        double maximum = 0.0;
        for (VisualizationEdge edge : edges) {
            VisualizationEdge required = Objects.requireNonNull(
                    edge,
                    "edge"
            );
            if (required.effectiveWeight() > maximum) {
                maximum = required.effectiveWeight();
            }
        }
        return maximum;
    }
}
