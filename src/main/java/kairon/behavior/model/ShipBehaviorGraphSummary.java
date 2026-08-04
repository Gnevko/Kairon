package kairon.behavior.model;

public record ShipBehaviorGraphSummary(
        GraphId graphId,
        String shipType,
        String shipName,
        String loadoutHash,
        int nodeCount,
        int edgeCount,
        int episodeCount,
        long totalOccurrenceCount,
        GraphCursor cursor
) {
}
