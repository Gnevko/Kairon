package kairon.system;

/**
 * Which kind of thing an entry in the registry is.
 *
 * <p>One constant per {@link SystemObject} implementation, so that a caller can
 * ask what something is without a pattern switch and so that a test can name a
 * kind without naming a class.</p>
 *
 * <p>{@link #UNCLASSIFIED} is a body known to exist whose kind nothing has
 * stated. It is a real answer rather than a placeholder: a scan carrying neither
 * {@code StarType} nor {@code PlanetClass}, and a body first heard of through a
 * signals record, both say a body is there and say nothing about what it is.
 * Guessing from its name is the one thing this registry never does.</p>
 */
public enum SystemObjectKind {

    STAR,
    PLANET,
    RING,
    BELT_CLUSTER,
    BARYCENTRE,
    UNCLASSIFIED;

    /**
     * The kind a record's {@code BodyType} states, or {@code UNCLASSIFIED}.
     *
     * <p>{@code BodyType} is the closed vocabulary an arrival carries —
     * {@code Null}, {@code Star}, {@code Planet}, {@code PlanetaryRing},
     * {@code StellarRing}, {@code Station}, {@code AsteroidCluster}. A station
     * is not a celestial body and states no kind here; it will be its own kind
     * of entry when stations are added, and until then leaving it unclassified
     * is more honest than filing it under something it is not.</p>
     */
    public static SystemObjectKind ofBodyType(String bodyType) {
        if (bodyType == null) {
            return UNCLASSIFIED;
        }
        return switch (bodyType.strip()) {
            case "Null" -> BARYCENTRE;
            case "Star" -> STAR;
            case "Planet" -> PLANET;
            case "PlanetaryRing", "StellarRing" -> RING;
            case "AsteroidCluster" -> BELT_CLUSTER;
            default -> UNCLASSIFIED;
        };
    }
}
