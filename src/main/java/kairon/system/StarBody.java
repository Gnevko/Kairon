package kairon.system;

import java.util.Objects;

/**
 * A star, as a scan reported it.
 *
 * <p>Every field beyond the profile is what a star reading carries and nothing
 * else carries. All of them are nullable because a star listed through another
 * body's parent chain is a star nobody has scanned: it is known to be there, and
 * that is all.</p>
 *
 * @param starType           the game's stellar classification, e.g. {@code K}
 * @param subclass           the heat subclass within that classification
 * @param luminosity         the luminosity class, e.g. {@code Va}
 * @param stellarMass        mass in multiples of the Sun's
 * @param radiusMetres       radius as the journal reports it, in metres
 * @param absoluteMagnitude  absolute magnitude
 * @param ageMillionYears    age in millions of years
 * @param surfaceTemperature surface temperature in kelvins
 */
public record StarBody(
        BodyProfile profile,
        String starType,
        Integer subclass,
        String luminosity,
        Double stellarMass,
        Double radiusMetres,
        Double absoluteMagnitude,
        Double ageMillionYears,
        Double surfaceTemperature
) implements SystemObject {

    public StarBody {
        profile = Objects.requireNonNull(profile, "profile");
    }

    /** A star known to be there, with nothing established about it. */
    public static StarBody listed(BodyProfile profile) {
        return new StarBody(
                profile, null, null, null, null, null, null, null, null
        );
    }

    @Override
    public SystemObjectKind kind() {
        return SystemObjectKind.STAR;
    }

    @Override
    public StarBody withProfile(BodyProfile updated) {
        return new StarBody(
                updated, starType, subclass, luminosity, stellarMass,
                radiusMetres, absoluteMagnitude, ageMillionYears,
                surfaceTemperature
        );
    }
}
