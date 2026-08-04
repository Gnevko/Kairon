package kairon.behavior.graph;

import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Lightweight immutable commit metadata for one observation.
 */
public record BehaviorGraphApplyResult(
        long busSequence,
        BehaviorGraphApplyStatus status,
        BehaviorGraphChangeSet changes,
        Optional<GraphId> activeGraphId,
        Optional<SystemEpisodeId> activeEpisodeId,
        Optional<GraphCursor> cursor,
        OptionalLong graphVersion,
        OptionalLong topologyVersion
) {

    public BehaviorGraphApplyResult {
        if (busSequence < 1) {
            throw new IllegalArgumentException("busSequence must be positive");
        }
        status = Objects.requireNonNull(status, "status");
        changes = Objects.requireNonNull(changes, "changes");
        activeGraphId = Objects.requireNonNull(
                activeGraphId,
                "activeGraphId"
        );
        activeEpisodeId = Objects.requireNonNull(
                activeEpisodeId,
                "activeEpisodeId"
        );
        cursor = Objects.requireNonNull(cursor, "cursor");
        graphVersion = Objects.requireNonNull(
                graphVersion,
                "graphVersion"
        );
        topologyVersion = Objects.requireNonNull(
                topologyVersion,
                "topologyVersion"
        );
        if (graphVersion.isPresent() != topologyVersion.isPresent()) {
            throw new IllegalArgumentException(
                    "graph and topology versions must be present together"
            );
        }
        graphVersion.ifPresent(version -> requireNonnegative(
                version,
                "graphVersion"
        ));
        topologyVersion.ifPresent(version -> requireNonnegative(
                version,
                "topologyVersion"
        ));
        if ((activeEpisodeId.isPresent() || cursor.isPresent())
                && activeGraphId.isEmpty()) {
            throw new IllegalArgumentException(
                    "episode and cursor require an active graph"
            );
        }
        if (activeGraphId.isPresent() != graphVersion.isPresent()) {
            throw new IllegalArgumentException(
                    "active graph and revisions must be present together"
            );
        }
        if (cursor.isPresent()
                && !cursor.orElseThrow().graphId().equals(
                        activeGraphId.orElseThrow()
                )) {
            throw new IllegalArgumentException(
                    "cursor must belong to the active graph"
            );
        }
        if (cursor.isPresent()
                && (activeEpisodeId.isEmpty()
                || !cursor.orElseThrow().episodeId().equals(
                        activeEpisodeId.orElseThrow()
                ))) {
            throw new IllegalArgumentException(
                    "cursor must belong to the active episode"
            );
        }
        if ((status == BehaviorGraphApplyStatus.DISABLED
                || status == BehaviorGraphApplyStatus.FAILED
                || status == BehaviorGraphApplyStatus.NO_GRAPH_ID)
                && (activeGraphId.isPresent()
                || activeEpisodeId.isPresent()
                || cursor.isPresent()
                || graphVersion.isPresent())) {
            throw new IllegalArgumentException(
                    status + " must not claim graph commit metadata"
            );
        }
        if (status == BehaviorGraphApplyStatus.APPLIED
                != changes.changed()) {
            throw new IllegalArgumentException(
                    "APPLIED must exactly describe a committed graph change"
            );
        }
    }

    public static BehaviorGraphApplyResult disabled(long busSequence) {
        return terminalWithoutGraph(
                busSequence,
                BehaviorGraphApplyStatus.DISABLED
        );
    }

    public static BehaviorGraphApplyResult failed(long busSequence) {
        return terminalWithoutGraph(
                busSequence,
                BehaviorGraphApplyStatus.FAILED
        );
    }

    public static BehaviorGraphApplyResult noGraphId(long busSequence) {
        return terminalWithoutGraph(
                busSequence,
                BehaviorGraphApplyStatus.NO_GRAPH_ID
        );
    }

    public static BehaviorGraphApplyResult notApplicable(long busSequence) {
        return terminalWithoutGraph(
                busSequence,
                BehaviorGraphApplyStatus.NOT_APPLICABLE
        );
    }

    private static BehaviorGraphApplyResult terminalWithoutGraph(
            long busSequence,
            BehaviorGraphApplyStatus status
    ) {
        return new BehaviorGraphApplyResult(
                busSequence,
                status,
                BehaviorGraphChangeSet.none(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                OptionalLong.empty(),
                OptionalLong.empty()
        );
    }

    private static void requireNonnegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    name + " must be nonnegative"
            );
        }
    }
}
