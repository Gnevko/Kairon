package kairon.behavior.model;

import java.util.Objects;

/**
 * Stable identity of one commander's concrete ship behavior graph.
 */
public record GraphId(String commanderFid, long shipId)
        implements Comparable<GraphId> {

    public GraphId {
        commanderFid = requireNonBlank(commanderFid, "commanderFid");
        if (shipId <= 0) {
            throw new IllegalArgumentException("shipId must be positive");
        }
    }

    public String canonicalValue() {
        return commanderFid + '/' + shipId;
    }

    @Override
    public int compareTo(GraphId other) {
        int commanderOrder = commanderFid.compareTo(other.commanderFid);
        return commanderOrder != 0
                ? commanderOrder
                : Long.compare(shipId, other.shipId);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
