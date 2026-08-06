package kairon.system;

import java.util.Objects;

/**
 * A centre of mass that bodies orbit.
 *
 * <p>Not a body: it has no class, no surface and no measurements, and
 * {@code ScanBaryCentre} reports nothing but where it is and how it moves. It is
 * an entry all the same, because the stars of a binary orbit it and their parent
 * chains name it — a hierarchy that skipped it would attach those stars to
 * nothing.</p>
 */
public record Barycentre(BodyProfile profile) implements SystemObject {

    public Barycentre {
        profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public SystemObjectKind kind() {
        return SystemObjectKind.BARYCENTRE;
    }

    @Override
    public Barycentre withProfile(BodyProfile updated) {
        return new Barycentre(updated);
    }
}
