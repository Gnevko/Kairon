package kairon.behavior.graph;

import kairon.behavior.normalize.NormalizedEventType;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Stable English names for normalized event types at the visualization
 * query boundary.
 */
public final class BehaviorGraphDisplayNameResolver {

    private static final Set<String> INITIALISMS = Set.of(
            "AFMU",
            "AX",
            "CQC",
            "DSS",
            "EDDN",
            "EDSM",
            "FC",
            "FSD",
            "FSS",
            "HGE",
            "ID",
            "NPC",
            "PVP",
            "SAA",
            "SLF",
            "SRV",
            "USS"
    );

    public String resolve(NormalizedEventType eventType) {
        Objects.requireNonNull(eventType, "eventType");
        String[] words = eventType.value().split("_");
        StringBuilder displayName = new StringBuilder(
                eventType.value().length()
        );
        for (String word : words) {
            if (!displayName.isEmpty()) {
                displayName.append(' ');
            }
            displayName.append(displayWord(word));
        }
        return displayName.toString();
    }

    private static String displayWord(String word) {
        if (INITIALISMS.contains(word)
                || word.chars().allMatch(Character::isDigit)) {
            return word;
        }
        String lowerCase = word.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lowerCase.charAt(0))
                + lowerCase.substring(1);
    }
}
