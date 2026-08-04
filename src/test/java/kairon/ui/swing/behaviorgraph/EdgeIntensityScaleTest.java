package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot
        .VisualizationEdge;
import kairon.behavior.normalize.NormalizedEventType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EdgeIntensityScaleTest {

    private static final double EPSILON = 1.0e-12;
    private static final EdgeIntensityScale SCALE =
            new EdgeIntensityScale();

    @Test
    void greaterEffectiveWeightProducesGreaterIntensity() {
        VisualizationEdge light = edge("A", "B", 1.0);
        VisualizationEdge heavy = edge("B", "C", 10.0);

        Map<VisualizationEdge, Double> intensities =
                SCALE.scale(List.of(light, heavy));

        assertTrue(intensities.get(heavy) > intensities.get(light));
    }

    @Test
    void equalWeightsProduceEqualIntensity() {
        VisualizationEdge first = edge("A", "B", 7.0);
        VisualizationEdge second = edge("C", "D", 7.0);

        Map<VisualizationEdge, Double> intensities =
                SCALE.scale(List.of(first, second));

        assertEquals(
                intensities.get(first),
                intensities.get(second),
                EPSILON
        );
    }

    @Test
    void zeroWeightRemainsVisibleAtMinimumIntensity() {
        VisualizationEdge zero = edge("A", "B", 0.0);
        VisualizationEdge positive = edge("B", "C", 3.0);

        assertEquals(
                SCALE.minimumIntensity(),
                SCALE.scale(List.of(zero, positive)).get(zero),
                EPSILON
        );
    }

    @Test
    void extremelyLargeEdgeDoesNotMakeSmallerEdgeInvisible() {
        VisualizationEdge small = edge("A", "B", 1.0);
        VisualizationEdge huge = edge("B", "C", 1.0e100);

        Map<VisualizationEdge, Double> intensities =
                SCALE.scale(List.of(small, huge));

        assertTrue(intensities.get(small) >= SCALE.minimumIntensity());
        assertTrue(intensities.get(small) > 0.0);
        assertEquals(
                SCALE.maximumIntensity(),
                intensities.get(huge),
                EPSILON
        );
    }

    @Test
    void emptyEdgeListProducesEmptyResult() {
        assertEquals(Map.of(), SCALE.scale(List.of()));
    }

    @Test
    void nonpositiveMaximumUsesMinimumIntensity() {
        assertEquals(
                SCALE.minimumIntensity(),
                SCALE.intensity(10.0, 0.0),
                EPSILON
        );
        assertEquals(
                SCALE.minimumIntensity(),
                SCALE.intensity(10.0, -5.0),
                EPSILON
        );
    }

    @Test
    void intensityAlwaysStaysInsideConfiguredRange() {
        double[] weights = {
                Double.NaN,
                Double.NEGATIVE_INFINITY,
                -10.0,
                0.0,
                0.001,
                1.0,
                1.0e12,
                Double.POSITIVE_INFINITY
        };
        for (double weight : weights) {
            double intensity = SCALE.intensity(weight, 1.0e12);
            assertTrue(intensity >= SCALE.minimumIntensity());
            assertTrue(intensity <= SCALE.maximumIntensity());
        }
    }

    @Test
    void scaleIsMonotonic() {
        double maximumWeight = 1.0e6;
        double[] weights = {
                0.0,
                0.001,
                0.1,
                1.0,
                10.0,
                1_000.0,
                maximumWeight
        };
        List<Double> intensities = new ArrayList<>();
        for (double weight : weights) {
            intensities.add(SCALE.intensity(
                    weight,
                    maximumWeight
            ));
        }

        for (int index = 1; index < intensities.size(); index++) {
            assertTrue(
                    intensities.get(index)
                            >= intensities.get(index - 1)
            );
        }
    }

    @Test
    void logarithmicNormalizationIsDeterministic() {
        double weight = 9.0;
        double maximumWeight = 99.0;
        double expected = SCALE.minimumIntensity()
                + Math.log1p(weight) / Math.log1p(maximumWeight)
                * (SCALE.maximumIntensity()
                - SCALE.minimumIntensity());

        assertEquals(
                expected,
                SCALE.intensity(weight, maximumWeight),
                EPSILON
        );
        assertEquals(
                SCALE.intensity(weight, maximumWeight),
                SCALE.intensity(weight, maximumWeight),
                0.0
        );
    }

    @Test
    void customRangeIsValidatedAndApplied() {
        EdgeIntensityScale custom =
                new EdgeIntensityScale(0.25, 0.85);

        assertEquals(0.25, custom.intensity(0.0, 10.0), EPSILON);
        assertEquals(0.85, custom.intensity(10.0, 10.0), EPSILON);
        assertThrows(
                IllegalArgumentException.class,
                () -> new EdgeIntensityScale(0.8, 0.2)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new EdgeIntensityScale(-0.1, 1.0)
        );
    }

    private static VisualizationEdge edge(
            String from,
            String to,
            double weight
    ) {
        return new VisualizationEdge(
                NormalizedEventType.of(from),
                NormalizedEventType.of(to),
                1L,
                weight
        );
    }
}
