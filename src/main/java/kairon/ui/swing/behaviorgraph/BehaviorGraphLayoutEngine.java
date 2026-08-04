package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.normalize.NormalizedEventType;

import java.awt.FontMetrics;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Computes immutable graph geometry before Swing painting begins.
 */
public interface BehaviorGraphLayoutEngine {

    LayoutResult layout(
            BehaviorGraphVisualizationSnapshot snapshot,
            FontMetrics fontMetrics
    );

    /**
     * Numeric layout result suitable for building cached render shapes.
     */
    record LayoutResult(
            Map<NormalizedEventType, NodeLayout> nodes,
            int canvasWidth,
            int canvasHeight
    ) {

        public LayoutResult {
            Objects.requireNonNull(nodes, "nodes");
            LinkedHashMap<NormalizedEventType, NodeLayout> copy =
                    new LinkedHashMap<>();
            nodes.forEach((eventType, layout) -> copy.put(
                    Objects.requireNonNull(eventType, "eventType"),
                    Objects.requireNonNull(layout, "layout")
            ));
            nodes = Collections.unmodifiableMap(copy);
            if (canvasWidth < 1 || canvasHeight < 1) {
                throw new IllegalArgumentException(
                        "canvas dimensions must be positive"
                );
            }
        }

        public Optional<NodeLayout> node(
                NormalizedEventType eventType
        ) {
            return Optional.ofNullable(
                    nodes.get(Objects.requireNonNull(
                            eventType,
                            "eventType"
                    ))
            );
        }
    }

    /**
     * Center, layer, and label bounds for one structural node.
     */
    record NodeLayout(
            int level,
            double centerX,
            double centerY,
            double labelX,
            double labelY,
            double labelWidth,
            double labelHeight
    ) {

        public NodeLayout {
            if (level < 0) {
                throw new IllegalArgumentException(
                        "level must be nonnegative"
                );
            }
            requireNonnegativeFinite(centerX, "centerX");
            requireNonnegativeFinite(centerY, "centerY");
            requireNonnegativeFinite(labelX, "labelX");
            requireNonnegativeFinite(labelY, "labelY");
            requireNonnegativeFinite(labelWidth, "labelWidth");
            requireNonnegativeFinite(labelHeight, "labelHeight");
        }

        public double labelRight() {
            return labelX + labelWidth;
        }

        public double labelBottom() {
            return labelY + labelHeight;
        }

        private static void requireNonnegativeFinite(
                double value,
                String name
        ) {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException(
                        name + " must be finite and nonnegative"
                );
            }
        }
    }
}
