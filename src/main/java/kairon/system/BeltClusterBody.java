package kairon.system;

import java.util.Objects;

/**
 * A belt cluster.
 *
 * <p>Reached only by a record that says so: an arrival naming an
 * {@code AsteroidCluster}. A scan of a belt cluster carries neither
 * {@code StarType} nor {@code PlanetClass} and is therefore
 * {@link UnclassifiedBody} until something states the kind — its name says what
 * it is, and its name is exactly what this registry does not read.</p>
 */
public record BeltClusterBody(BodyProfile profile) implements SystemObject {

    public BeltClusterBody {
        profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public SystemObjectKind kind() {
        return SystemObjectKind.BELT_CLUSTER;
    }

    @Override
    public BeltClusterBody withProfile(BodyProfile updated) {
        return new BeltClusterBody(updated);
    }
}
