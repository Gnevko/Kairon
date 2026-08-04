package kairon.behavior.classify;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.normalize.NormalizedBehaviorEvent;
import kairon.behavior.normalize.NormalizedEventType;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Defines the structural granularity of repeat-heavy journal observations.
 *
 * <p>The Journal can emit several material, attack, or fuel progress records
 * for one uninterrupted activity. The graph retains the first record as the
 * representative occurrence and suppresses only an immediately continuing
 * run with the same projection key. No time threshold, semantic importance,
 * or synthetic completion event is inferred. The authoritative raw records
 * remain available in their Journal source.</p>
 */
public final class BehaviorOccurrenceProjectionPolicy {

    private static final Set<NormalizedEventType> REPEATABLE_TYPES = Set.of(
            NormalizedEventType.MATERIAL_COLLECTED,
            NormalizedEventType.UNDER_ATTACK,
            NormalizedEventType.FUEL_SCOOPING
    );

    /**
     * Returns whether a candidate should create a structural occurrence.
     */
    public boolean shouldRecord(
            EventOccurrence previous,
            NormalizedBehaviorEvent candidate
    ) {
        Objects.requireNonNull(candidate, "candidate");
        if (previous == null
                || !previous.eventType().equals(candidate.eventType())
                || !REPEATABLE_TYPES.contains(candidate.eventType())) {
            return true;
        }
        return !projectionKey(
                previous.eventType(),
                previous.attributes().get("Target")
        ).equals(projectionKey(
                candidate.eventType(),
                candidate.attributes().get("Target")
        ));
    }

    private static String projectionKey(
            NormalizedEventType eventType,
            JsonNode target
    ) {
        if (!eventType.equals(NormalizedEventType.UNDER_ATTACK)) {
            return eventType.value();
        }
        String normalizedTarget = target != null && target.isTextual()
                ? target.textValue().strip().toUpperCase(Locale.ROOT)
                : "";
        return eventType.value()
                + "|target="
                + (normalizedTarget.isEmpty() ? "UNKNOWN" : normalizedTarget);
    }
}
