package kairon.state;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What is established about one body, kept for as long as the Commander is
 * this Commander.
 *
 * <p>{@code signalCounts} is the whole reported reading, keyed by the category
 * the scanner named. It is the source of truth: the biological and geological
 * counts the snapshot publishes are read out of it rather than stored beside
 * it, so a category the game adds is retained instead of discarded on the way
 * in.</p>
 */
record BodyContext(
        Map<String, Integer> signalCounts,
        Boolean landable,
        Boolean wasDiscovered,
        Boolean wasMapped,
        Boolean wasFootfalled,
        Double distanceFromArrivalLs,
        String bodyName,
        String bodyType,
        String planetClass,
        String starType
) {

    static final String BIOLOGICAL = "BIOLOGICAL";
    static final String GEOLOGICAL = "GEOLOGICAL";

    BodyContext {
        Objects.requireNonNull(signalCounts, "signalCounts");
        signalCounts = Map.copyOf(new TreeMap<>(signalCounts));
    }

    Integer biologicalSignalCount() {
        return signalCounts.get(BIOLOGICAL);
    }

    Integer geologicalSignalCount() {
        return signalCounts.get(GEOLOGICAL);
    }
}
