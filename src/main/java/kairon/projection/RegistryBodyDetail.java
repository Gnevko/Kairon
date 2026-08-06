package kairon.projection;

import kairon.behavior.context.BodyDetail;
import kairon.behavior.context.BodyDetailLookup;
import kairon.semantics.BodySurveyFacts;
import kairon.system.PlanetBody;
import kairon.system.StarBody;
import kairon.system.SystemObject;
import kairon.system.SystemObjectKind;
import kairon.system.SystemRegistrySnapshot;

import java.util.Objects;

/**
 * The current system's bodies, said in the terms the behaviour graph counts by.
 *
 * <p>This is the whole of the connection between the two peer projections, and
 * it is deliberately here. The graph does not know that a registry exists — it
 * receives {@link BodyDetail}, which is plain values — and the registry does not
 * know that a graph exists. Package {@code kairon.projection} is the one place
 * that already reads both, and it is where the coordinator applies them in
 * order, so the translation costs neither of them a dependency.</p>
 *
 * <p>Fail closed on identity. A snapshot that could not answer, one describing
 * another system, and a body it holds nothing for all give
 * {@link BodyDetail#UNKNOWN}: a body id repeats across systems, and detail
 * filed under the wrong body is worse than no detail.</p>
 */
public final class RegistryBodyDetail implements BodyDetailLookup {

    private final SystemRegistrySnapshot registry;

    public RegistryBodyDetail(SystemRegistrySnapshot registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public BodyDetail detailOf(Long systemAddress, Long bodyId) {
        if (bodyId == null
                || !registry.available()
                || !Objects.equals(registry.systemAddress(), systemAddress)) {
            return BodyDetail.UNKNOWN;
        }
        SystemObject body = registry.object(bodyId);
        return body == null ? BodyDetail.UNKNOWN : detailOf(body);
    }

    /**
     * One recorded object, read for what it is.
     *
     * <p>The coarse type is the kind the registry classified the object as
     * rather than whatever record last happened to carry a {@code BodyType}
     * field, and an object nothing has classified states no type at all. The
     * class and the stellar type are each read from the only kind of object
     * that can have one.</p>
     */
    private static BodyDetail detailOf(SystemObject body) {
        return new BodyDetail(
                body.kind() == SystemObjectKind.UNCLASSIFIED
                        ? null
                        : body.kind().name(),
                body instanceof PlanetBody planet
                        ? planet.planetClass()
                        : null,
                body instanceof StarBody star ? star.starType() : null,
                body instanceof PlanetBody planet ? planet.landable() : null,
                body.profile().wasDiscovered(),
                body.profile().wasMapped(),
                body.profile().wasFootfalled(),
                body.profile().distanceFromArrivalLs(),
                body.profile().signalCounts().get(BodySurveyFacts.BIOLOGICAL),
                body.profile().signalCounts().get(BodySurveyFacts.GEOLOGICAL)
        );
    }
}
