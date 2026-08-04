package kairon.state;

/**
 * Canonical-to-legacy body type compatibility projection.
 *
 * <p>Projects three canonical body dimensions into the single legacy scalar
 * value the behavior graph's {@code ContextSnapshot.bodyType} carries. It is a
 * compatibility projection, never the canonical classification: the canonical
 * dimensions are {@code broadBodyType}, {@code planetClass} and
 * {@code starType}, and the model-facing context uses those three.</p>
 */
public final class BodyTypeCompatibilityProjection {

    private BodyTypeCompatibilityProjection() {
    }

    public static String compatibleBodyType(
            String broadBodyType,
            String planetClass,
            String starType
    ) {
        return compatibleBodyTypeInternal(
                normalizeOptional(broadBodyType),
                normalizeOptional(planetClass),
                normalizeOptional(starType)
        );
    }

    public static String compatibleBodyType(
            CurrentGameStateSnapshot snapshot
    ) {
        if (snapshot == null) {
            return null;
        }
        return compatibleBodyTypeInternal(
                normalizeOptional(snapshot.broadBodyType()),
                normalizeOptional(snapshot.planetClass()),
                normalizeOptional(snapshot.starType())
        );
    }

    private static String compatibleBodyTypeInternal(
            String broadBodyType,
            String planetClass,
            String starType
    ) {
        if (isPlanet(broadBodyType)) {
            return planetClass != null ? planetClass : broadBodyType;
        }

        if (isStar(broadBodyType)) {
            return starType != null ? starType : broadBodyType;
        }

        if (broadBodyType != null) {
            return broadBodyType;
        }

        if (planetClass != null && starType == null) {
            return planetClass;
        }

        if (planetClass == null && starType != null) {
            return starType;
        }

        return null;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isPlanet(String value) {
        return value != null && value.equalsIgnoreCase("planet");
    }

    private static boolean isStar(String value) {
        return value != null && value.equalsIgnoreCase("star");
    }
}

