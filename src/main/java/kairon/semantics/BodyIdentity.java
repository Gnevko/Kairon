package kairon.semantics;

import java.util.Objects;

/**
 * Which body, told apart by address and id and never by name.
 *
 * <p>One value, used wherever a layer means <em>this body</em>: a scanner
 * reading, the canonical per-body registry, the body an occurrence happened at,
 * and the body a visit arrived at. Those were four records of the same two
 * fields, in four packages, and the point of a body identity is that two layers
 * asking "is this the same body?" get the same answer — which four independent
 * definitions can only make likely.</p>
 *
 * <p>Identity only. A display name is not here, and neither is anything the
 * body <em>is</em>: two readings of one body report different distances,
 * different signal sets and eventually different survey flags, and none of that
 * changes which body it is. A name is worse than useless as identity — two
 * systems name their moons alike, and a reading filed under the wrong body is
 * worse than a reading dropped.</p>
 *
 * <p>Ordered, so it can key a sorted map without a comparator being restated at
 * every use. The order is the natural one on the pair and carries no meaning of
 * its own.</p>
 */
public record BodyIdentity(long systemAddress, long bodyId)
        implements Comparable<BodyIdentity> {

    @Override
    public int compareTo(BodyIdentity other) {
        Objects.requireNonNull(other, "other");
        int addressOrder = Long.compare(systemAddress, other.systemAddress);
        return addressOrder != 0
                ? addressOrder
                : Long.compare(bodyId, other.bodyId);
    }
}
