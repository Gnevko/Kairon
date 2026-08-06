package kairon.behavior.context;

/**
 * What is established about one body, in the terms the graph counts by.
 *
 * <p>Canonical state used to carry these beside the Commander's position, as
 * facts of "the current body". They belong to the star system rather than to
 * the ship, and they now live in the current-system registry
 * ({@code ADR-0025}); this is the shape they arrive in.</p>
 *
 * <p>Plain values only, so the graph never learns that a registry exists. The
 * translation is done by whoever holds both, which is the projection
 * coordinator — two peer projections that read each other are two projections
 * that drift.</p>
 *
 * <p>Every field is nullable and null means unestablished. A count is absent
 * until a reading positively established one; absence is never zero.</p>
 */
public record BodyDetail(
        String broadBodyType,
        String planetClass,
        String starType,
        Boolean landable,
        Boolean wasDiscovered,
        Boolean wasMapped,
        Boolean wasFootfalled,
        Double distanceFromArrivalLs,
        Integer biologicalSignalCount,
        Integer geologicalSignalCount
) {

    /** A body nothing is established about, and the answer for no body at all. */
    public static final BodyDetail UNKNOWN = new BodyDetail(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
    );

    /**
     * Whether anything grows here, or null when nobody has counted.
     *
     * <p>Derived from the biological count rather than stored beside it: two
     * fields for one fact is how they come to disagree. Only a positive count
     * is ever recorded, so in practice this answers true or unknown — which is
     * the truth of it, because no source states that a body has none.</p>
     */
    public Boolean hasBiology() {
        return biologicalSignalCount == null
                ? null
                : biologicalSignalCount > 0;
    }
}
