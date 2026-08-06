package kairon.system;

import java.util.Objects;

/**
 * A ring around a star or a planet.
 *
 * <p>Reached by being named as a parent: a body orbiting inside a ring carries
 * {@code Ring} in its chain, and an arrival record calls the thing it arrived at
 * a {@code PlanetaryRing} or a {@code StellarRing}. A ring is also listed in its
 * parent body's own {@code Rings} array, and it is deliberately not stored a
 * second time from there — one ring described in two places eventually disagrees
 * with itself.</p>
 */
public record RingBody(BodyProfile profile) implements SystemObject {

    public RingBody {
        profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public SystemObjectKind kind() {
        return SystemObjectKind.RING;
    }

    @Override
    public RingBody withProfile(BodyProfile updated) {
        return new RingBody(updated);
    }
}
