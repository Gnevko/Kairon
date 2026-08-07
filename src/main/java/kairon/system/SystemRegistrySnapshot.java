package kairon.system;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The star system the Commander is in, as it stood after one observation.
 *
 * <p>Immutable and captured at the projection boundary, beside the canonical
 * state and the behaviour situation. Anything that wants to know what the system
 * holds reads a snapshot it was handed; nothing calls back into the live
 * registry, because building a decision request must never perform a late read
 * of a service that has moved on since.</p>
 *
 * <p>{@code objects} is keyed by body id, which is identity enough here: a
 * snapshot is of one system, and the system is named beside it. The tree is not
 * stored — every object carries the parent chain the journal stated, and a
 * partially surveyed system has bodies whose place is known and whose parents
 * have not been scanned. Links from parent to child would need those unscanned
 * bodies invented.</p>
 *
 * <p>{@code bodyCount} and {@code nonBodyCount} are the system totals a
 * discovery scan reported; {@code allBodiesFound} is that scan's completion
 * confirmed. All three are absent until the honk, and absent is not zero.</p>
 */
public record SystemRegistrySnapshot(
        long busSequence,
        boolean available,
        Long systemAddress,
        String systemName,
        Integer bodyCount,
        Integer nonBodyCount,
        boolean allBodiesFound,
        Map<Long, SystemObject> objects
) {

    public SystemRegistrySnapshot {
        objects = Collections.unmodifiableMap(new TreeMap<>(
                Objects.requireNonNull(objects, "objects")
        ));
    }

    /**
     * A registry that has recorded nothing.
     *
     * <p>What is captured before any visit has begun, and again after one ends.
     * Distinct from {@link #unavailable}: this one answered, and the answer is
     * that the Commander is in no system it knows anything about.</p>
     */
    public static SystemRegistrySnapshot empty(long busSequence) {
        return new SystemRegistrySnapshot(
                busSequence, true, null, null, null, null, false, Map.of()
        );
    }

    /**
     * What is published when the registry failed on this observation.
     *
     * <p>An explicit absence rather than a stale copy. A reader must be able to
     * tell "this system holds nothing" from "the registry could not say", and a
     * silently reused previous snapshot says the first while meaning the
     * second.</p>
     */
    public static SystemRegistrySnapshot unavailable(long busSequence) {
        return new SystemRegistrySnapshot(
                busSequence, false, null, null, null, null, false, Map.of()
        );
    }

    /** The object with this body id, or null when none is recorded. */
    public SystemObject object(long bodyId) {
        return objects.get(bodyId);
    }

    /**
     * Whether this describes the same system in the same detail as another.
     *
     * <p>Everything except which observation the snapshot was taken after. Most
     * observations tell the registry nothing — a docking request says nothing
     * about a moon — so a reader that refreshes on every publication refreshes
     * almost always for nothing. This is how such a reader tells the difference
     * without comparing rendered output.</p>
     */
    public boolean sameContentAs(SystemRegistrySnapshot other) {
        return other != null
                && available == other.available
                && allBodiesFound == other.allBodiesFound
                && Objects.equals(systemAddress, other.systemAddress)
                && Objects.equals(systemName, other.systemName)
                && Objects.equals(bodyCount, other.bodyCount)
                && Objects.equals(nonBodyCount, other.nonBodyCount)
                && objects.equals(other.objects);
    }

    /** How many recorded objects have been scanned or better. */
    public long scannedCount() {
        return objects.values().stream()
                .filter(object -> object.knowledge()
                        != BodyKnowledgeLevel.LISTED)
                .count();
    }

    /**
     * How many scanned objects are of the kinds {@link #bodyCount} counts.
     *
     * <p>Stars and planets, because that is what a discovery scan totals. Read
     * off a measured journal rather than assumed: Schieni GG-A c3-64 reported
     * {@code BodyCount: 9} and the Commander took eight planet readings plus
     * the arrival star's. Barycentres, rings and belt clusters are recorded all
     * the same; they are simply not what the total is a total of.</p>
     *
     * <p>Separate from {@link #scannedCount} on purpose. That one answers "how
     * much of what I hold has been read", which is the question the desktop
     * view asks; this one is the only one comparable with a stated total, and
     * two numbers that are not comparable must not share a name.</p>
     */
    public long scannedBodyCount() {
        return objects.values().stream()
                .filter(object -> object.knowledge()
                        != BodyKnowledgeLevel.LISTED)
                .filter(object -> object.kind() == SystemObjectKind.STAR
                        || object.kind() == SystemObjectKind.PLANET)
                .count();
    }

    /** Whether the registry holds nothing at all. */
    public boolean isEmpty() {
        return objects.isEmpty();
    }
}
