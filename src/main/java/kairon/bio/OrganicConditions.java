package kairon.bio;

/**
 * The conditions on a body, in the units the spawn rules are written in.
 *
 * <p>Six readings and nothing else, all of them from the detailed scan that
 * established the body. The units are the upstream table's, not the journal's,
 * and the conversion happens once here rather than at every comparison: the
 * game reports gravity in metres per second squared and pressure in pascals,
 * and the rules are written in standard gravities and atmospheres. A threshold
 * and the value it is compared against must be in the same unit, and the only
 * way to be sure of that is for one place to own both.</p>
 *
 * <p>Every field is nullable, because a body may be known without having been
 * scanned. A rule that constrains a dimension nothing was reported for cannot
 * be satisfied and cannot be refuted; {@link OrganicRuleset} decides which,
 * and it decides the same way for all six.</p>
 *
 * @param planetClass  the game's own classification, e.g. {@code Rocky body}
 * @param atmosphere   the atmosphere's principal component, e.g.
 *                     {@code CarbonDioxide}, or {@code None}
 * @param volcanism    the described volcanism, e.g.
 *                     {@code major water geysers volcanism}, or {@code None}
 * @param gravity      surface gravity in standard gravities
 * @param temperature  surface temperature in kelvins
 * @param pressure     surface pressure in atmospheres
 */
public record OrganicConditions(
        String planetClass,
        String atmosphere,
        String volcanism,
        Double gravity,
        Double temperature,
        Double pressure
) {

    /** Standard gravity, the unit the rules use and the journal does not. */
    private static final double STANDARD_GRAVITY = 9.80665;

    /** One atmosphere in pascals, likewise. */
    private static final double STANDARD_PRESSURE = 101_325.0;

    /**
     * What the game says a body has, read as what the rules ask about.
     *
     * <p>An unreported atmosphere and an unreported volcanism are both read as
     * {@code None}: the journal writes the absence as a blank, and a rule
     * asking for no atmosphere has to be able to match a body that has none.
     * The two named quantities are converted; a missing one stays missing,
     * because zero gravity is a claim and silence is not.</p>
     */
    public static OrganicConditions ofScan(
            String planetClass,
            String atmosphereType,
            String volcanism,
            Double surfaceGravityMetresPerSecondSquared,
            Double surfaceTemperatureKelvin,
            Double surfacePressurePascals
    ) {
        return new OrganicConditions(
                blankToNull(planetClass),
                stated(atmosphereType),
                stated(volcanism),
                divided(surfaceGravityMetresPerSecondSquared, STANDARD_GRAVITY),
                surfaceTemperatureKelvin,
                divided(surfacePressurePascals, STANDARD_PRESSURE)
        );
    }

    private static String stated(String value) {
        String text = value == null ? "" : value.strip();
        return text.isEmpty() ? "None" : text;
    }

    private static String blankToNull(String value) {
        String text = value == null ? "" : value.strip();
        return text.isEmpty() ? null : text;
    }

    private static Double divided(Double value, double unit) {
        return value == null ? null : value / unit;
    }
}
