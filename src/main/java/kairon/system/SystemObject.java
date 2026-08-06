package kairon.system;

import kairon.semantics.BodyIdentity;

import java.util.List;

/**
 * One thing in the star system the Commander is in.
 *
 * <p>A sealed hierarchy rather than one record with a field per possibility. A
 * star has a stellar class and no atmosphere; a planet has an atmosphere and no
 * luminosity; a barycentre has neither and is still a real entry, because bodies
 * orbit it. Written as one record those become a form with blanks, and a form
 * with blanks is how a star and a planet came to be described as one body.</p>
 *
 * <p>What every kind has is in {@link BodyProfile}, reached through the
 * delegating accessors here so a caller that does not care what kind of object
 * this is never has to ask.</p>
 *
 * <p>A moon is not a kind. It is a {@link PlanetBody} whose parent is a
 * {@link PlanetBody} — the game does not distinguish them, the journal does not
 * distinguish them, and a class for it would be a distinction invented here.</p>
 */
public sealed interface SystemObject
        permits StarBody, PlanetBody, RingBody, BeltClusterBody,
                Barycentre, UnclassifiedBody {

    /** What this object has by virtue of being an object in a system. */
    BodyProfile profile();

    /** Which kind of thing this is. */
    SystemObjectKind kind();

    /** The same object carrying a different profile. */
    SystemObject withProfile(BodyProfile profile);

    default BodyIdentity identity() {
        return profile().identity();
    }

    default long bodyId() {
        return profile().bodyId();
    }

    default String name() {
        return profile().name();
    }

    default List<BodyParent> parents() {
        return profile().parents();
    }

    default BodyKnowledgeLevel knowledge() {
        return profile().knowledge();
    }

    default BiologicalSurvey biology() {
        return profile().biology();
    }

    /**
     * Whether this object orbits something of the given kind at any depth.
     *
     * <p>Reads the stated chain and nothing else. A body whose chain has not
     * been stated answers false to everything, which is the fail-closed answer
     * and not a claim that it orbits nothing.</p>
     */
    default boolean orbits(ParentKind parentKind) {
        return parents().stream()
                .anyMatch(parent -> parent.kind() == parentKind);
    }

    /** The body this one immediately orbits, or null when none was stated. */
    default BodyParent immediateParent() {
        return parents().isEmpty() ? null : parents().getFirst();
    }
}
