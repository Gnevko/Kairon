package kairon.system;

import java.util.Objects;

/**
 * A body known to be there whose kind nothing has stated.
 *
 * <p>A real answer, not a placeholder. Two ordinary records reach it: a scan
 * carrying neither {@code StarType} nor {@code PlanetClass} — a belt cluster,
 * for instance — and a signals record, which files a reading under a body id and
 * says nothing about what the body is.</p>
 *
 * <p>It stops being unclassified when something states the kind, and never
 * because its name looks like one. Reading {@code "… Belt Cluster 1"} out of a
 * body name is the guess this registry exists not to make.</p>
 */
public record UnclassifiedBody(BodyProfile profile) implements SystemObject {

    public UnclassifiedBody {
        profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public SystemObjectKind kind() {
        return SystemObjectKind.UNCLASSIFIED;
    }

    @Override
    public UnclassifiedBody withProfile(BodyProfile updated) {
        return new UnclassifiedBody(updated);
    }
}
