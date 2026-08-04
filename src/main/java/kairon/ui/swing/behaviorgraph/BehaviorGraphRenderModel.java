package kairon.ui.swing.behaviorgraph;

import kairon.behavior.normalize.NormalizedEventType;

import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Prepared, query-free data consumed by {@link BehaviorGraphCanvas}.
 */
public record BehaviorGraphRenderModel(
        int canvasWidth,
        int canvasHeight,
        Map<NormalizedEventType, NodeRenderData> nodes,
        List<EdgeRenderData> edges,
        Optional<NormalizedEventType> currentNode,
        long graphVersion,
        long topologyVersion
) {

    public BehaviorGraphRenderModel {
        if (canvasWidth < 1 || canvasHeight < 1) {
            throw new IllegalArgumentException(
                    "canvas dimensions must be positive"
            );
        }
        nodes = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(nodes, "nodes")
        ));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        currentNode = Objects.requireNonNull(currentNode, "currentNode");
    }

    public record NodeRenderData(
            NormalizedEventType eventType,
            String label,
            long activeEpisodeOccurrenceCount,
            double centerX,
            double centerY,
            int level,
            Ellipse2D.Double circleBounds,
            Rectangle2D.Double labelBounds,
            boolean current
    ) {

        public NodeRenderData {
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(label, "label");
            if (activeEpisodeOccurrenceCount < 0) {
                throw new IllegalArgumentException(
                        "activeEpisodeOccurrenceCount must be nonnegative"
                );
            }
            if (level < 0) {
                throw new IllegalArgumentException(
                        "level must be nonnegative"
                );
            }
            circleBounds = copyOf(circleBounds, "circleBounds");
            labelBounds = copyOf(labelBounds, "labelBounds");
        }

        private static Ellipse2D.Double copyOf(
                Ellipse2D.Double value,
                String name
        ) {
            Objects.requireNonNull(value, name);
            return new Ellipse2D.Double(
                    value.x,
                    value.y,
                    value.width,
                    value.height
            );
        }

        private static Rectangle2D.Double copyOf(
                Rectangle2D.Double value,
                String name
        ) {
            Objects.requireNonNull(value, name);
            return new Rectangle2D.Double(
                    value.x,
                    value.y,
                    value.width,
                    value.height
            );
        }
    }

    public record EdgeRenderData(
            NormalizedEventType from,
            NormalizedEventType to,
            Path2D.Double path,
            Path2D.Double arrowHead,
            double effectiveWeight,
            double intensity,
            EdgeRenderKind kind
    ) {

        public EdgeRenderData {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            path = copyOf(path, "path");
            arrowHead = copyOf(arrowHead, "arrowHead");
            if (!Double.isFinite(effectiveWeight)) {
                throw new IllegalArgumentException(
                        "effectiveWeight must be finite"
                );
            }
            if (!Double.isFinite(intensity)
                    || intensity < 0.0
                    || intensity > 1.0) {
                throw new IllegalArgumentException(
                        "intensity must be in [0, 1]"
                );
            }
            Objects.requireNonNull(kind, "kind");
        }

        private static Path2D.Double copyOf(
                Path2D.Double value,
                String name
        ) {
            Objects.requireNonNull(value, name);
            return (Path2D.Double) value.clone();
        }
    }

    public enum EdgeRenderKind {
        FORWARD,
        SAME_LEVEL,
        BACKWARD,
        SELF
    }
}
