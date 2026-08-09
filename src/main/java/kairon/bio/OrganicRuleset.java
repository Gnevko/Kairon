package kairon.bio;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One set of conditions under which a species is known to grow.
 *
 * <p>A species has several of these and needs to satisfy only one — the same
 * organism grows in more than one place, under conditions that do not overlap.
 * Every constraint is optional; a ruleset that constrains nothing admits every
 * body, which is what an upstream entry whose only constraints this project
 * does not read comes out as.</p>
 *
 * <h2>What is not here</h2>
 * <p>The upstream also constrains by galactic region, by the system's star
 * class, by materials on the body and by what else is in the system. None of
 * those is read (ADR-0030). Dropping a constraint can only <em>widen</em> the
 * set of species a body admits, and the one thing the answer is used for is a
 * floor over that set — a wider set can only lower a minimum, never raise it
 * above the truth. Measured against this Commander's own journals, 87% of the
 * wrong species that survive would have been removed by region alone; that is
 * the next tightening, not a correctness gap.</p>
 *
 * @param bodyTypes      the planet classes admitted, empty for any
 * @param atmospheres    the atmosphere components admitted, empty for any
 * @param minGravity     inclusive lower bound in standard gravities, or null
 * @param maxGravity     inclusive upper bound in standard gravities, or null
 * @param minTemperature inclusive lower bound in kelvins, or null
 * @param maxTemperature inclusive upper bound in kelvins, or null
 * @param minPressure    inclusive lower bound in atmospheres, or null
 * @param maxPressure    inclusive upper bound in atmospheres, or null
 * @param volcanism      what the body's volcanism must be
 */
public record OrganicRuleset(
        List<String> bodyTypes,
        List<String> atmospheres,
        Double minGravity,
        Double maxGravity,
        Double minTemperature,
        Double maxTemperature,
        Double minPressure,
        Double maxPressure,
        Volcanism volcanism
) {

    /**
     * What a ruleset demands of a body's volcanism.
     *
     * <p>Three shapes, which is how the upstream writes it: no demand at all,
     * the word {@code None}, the word {@code Any}, or a list of fragments to
     * find in the body's own volcanism text. {@code Any} is read as <em>some
     * volcanism, of any kind</em> rather than as no demand: all three readings
     * were measured and this one is both the tighter and the one the word
     * means.</p>
     */
    public record Volcanism(Demand demand, List<String> fragments) {

        /** No demand about volcanism at all. */
        public static final Volcanism UNCONSTRAINED =
                new Volcanism(Demand.UNCONSTRAINED, List.of());

        /** The body must have none. */
        public static final Volcanism ABSENT =
                new Volcanism(Demand.ABSENT, List.of());

        /** The body must have some, of any kind. */
        public static final Volcanism PRESENT =
                new Volcanism(Demand.PRESENT, List.of());

        public Volcanism {
            demand = Objects.requireNonNull(demand, "demand");
            fragments = List.copyOf(Objects.requireNonNull(fragments, "fragments"));
        }

        /** The body's volcanism text must contain one of {@code fragments}. */
        public static Volcanism containingOneOf(List<String> fragments) {
            return fragments == null || fragments.isEmpty()
                    ? UNCONSTRAINED
                    : new Volcanism(Demand.ONE_OF, fragments);
        }

        public enum Demand {
            UNCONSTRAINED, ABSENT, PRESENT, ONE_OF
        }

        boolean admits(String stated) {
            String text = stated == null || stated.isBlank()
                    ? "None"
                    : stated.strip();
            boolean none = "None".equalsIgnoreCase(text);
            return switch (demand) {
                case UNCONSTRAINED -> true;
                case ABSENT -> none;
                case PRESENT -> !none;
                case ONE_OF -> !none && fragments.stream().anyMatch(fragment ->
                        text.toLowerCase(Locale.ROOT)
                                .contains(fragment.toLowerCase(Locale.ROOT)));
            };
        }
    }

    /** Constrains nothing, and therefore admits every body. */
    public static final OrganicRuleset UNCONSTRAINED = new OrganicRuleset(
            List.of(), List.of(), null, null, null, null, null, null,
            Volcanism.UNCONSTRAINED
    );

    public OrganicRuleset {
        bodyTypes = Collections.unmodifiableList(
                List.copyOf(Objects.requireNonNull(bodyTypes, "bodyTypes"))
        );
        atmospheres = Collections.unmodifiableList(
                List.copyOf(Objects.requireNonNull(atmospheres, "atmospheres"))
        );
        volcanism = volcanism == null ? Volcanism.UNCONSTRAINED : volcanism;
    }

    /**
     * Whether a body with these conditions could grow the species.
     *
     * <p>A dimension the scan did not report is <em>not</em> a refusal: the
     * body is admitted on it and refused only by what is actually known. The
     * alternative — treating silence as a mismatch — would drop species from a
     * body that was scanned incompletely, and dropping the true species is the
     * one failure the floor this feeds cannot survive.</p>
     */
    public boolean admits(OrganicConditions conditions) {
        Objects.requireNonNull(conditions, "conditions");
        if (!bodyTypes.isEmpty()
                && conditions.planetClass() != null
                && !bodyTypes.contains(conditions.planetClass())) {
            return false;
        }
        if (!atmospheres.isEmpty()
                && conditions.atmosphere() != null
                && !atmospheres.contains(conditions.atmosphere())) {
            return false;
        }
        return within(conditions.gravity(), minGravity, maxGravity)
                && within(conditions.temperature(), minTemperature, maxTemperature)
                && within(conditions.pressure(), minPressure, maxPressure)
                && volcanism.admits(conditions.volcanism());
    }

    private static boolean within(Double value, Double minimum, Double maximum) {
        if (value == null) {
            return true;
        }
        return (minimum == null || value >= minimum - TOLERANCE)
                && (maximum == null || value <= maximum + TOLERANCE);
    }

    /**
     * Slack on a bound, because the two sides are rounded differently.
     *
     * <p>The rules are written to three decimal places and the journal reports
     * gravity to six. A body exactly on a published bound would otherwise fall
     * outside it on the last digit, and falling outside is what drops the true
     * species.</p>
     */
    private static final double TOLERANCE = 1e-6;
}
