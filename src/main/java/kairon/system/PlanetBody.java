package kairon.system;

import java.util.Objects;

/**
 * A planet or a moon, as a scan reported it.
 *
 * <p>One class for both. Which of the two a body is, is which kind of thing it
 * orbits, and the parent chain already says that — {@link #isMoon()} reads it
 * rather than restating it as a field that could disagree.</p>
 *
 * <p>All fields beyond the profile are nullable: a planet named in another
 * body's parent chain is known to be there and nothing more.</p>
 *
 * @param planetClass        the game's classification, e.g. {@code Icy body}
 * @param atmosphere         the described atmosphere, blank when there is none
 * @param atmosphereType     the atmosphere's principal component
 * @param volcanism          the described volcanism
 * @param terraformState     terraformable, terraforming or terraformed
 * @param landable           whether the ship can put down on it
 * @param tidalLock          whether it keeps one face to what it orbits
 * @param massEarthMasses    mass in multiples of Earth's
 * @param radiusMetres       radius as the journal reports it, in metres
 * @param surfaceGravity     surface gravity in metres per second squared
 * @param surfaceTemperature surface temperature in kelvins
 * @param surfacePressure    surface pressure in pascals
 */
public record PlanetBody(
        BodyProfile profile,
        String planetClass,
        String atmosphere,
        String atmosphereType,
        String volcanism,
        String terraformState,
        Boolean landable,
        Boolean tidalLock,
        Double massEarthMasses,
        Double radiusMetres,
        Double surfaceGravity,
        Double surfaceTemperature,
        Double surfacePressure
) implements SystemObject {

    public PlanetBody {
        profile = Objects.requireNonNull(profile, "profile");
    }

    /** A planet known to be there, with nothing established about it. */
    public static PlanetBody listed(BodyProfile profile) {
        return new PlanetBody(
                profile, null, null, null, null, null, null, null,
                null, null, null, null, null
        );
    }

    @Override
    public SystemObjectKind kind() {
        return SystemObjectKind.PLANET;
    }

    /** Whether what this orbits is itself a planet. */
    public boolean isMoon() {
        BodyParent parent = immediateParent();
        return parent != null && parent.kind() == ParentKind.PLANET;
    }

    @Override
    public PlanetBody withProfile(BodyProfile updated) {
        return new PlanetBody(
                updated, planetClass, atmosphere, atmosphereType, volcanism,
                terraformState, landable, tidalLock, massEarthMasses,
                radiusMetres, surfaceGravity, surfaceTemperature,
                surfacePressure
        );
    }
}
