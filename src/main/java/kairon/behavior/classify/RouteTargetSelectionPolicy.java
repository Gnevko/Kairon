package kairon.behavior.classify;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.normalize.NormalizedBehaviorEvent;
import kairon.behavior.normalize.NormalizedEventType;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Whether a route-target record states a target that is already stated.
 *
 * <p>The journal republishes {@code FSDTarget} for a route that has not
 * changed — the same system, the same remaining jumps, restated around a jump
 * the Commander had already begun. Each publication used to become a structural
 * occurrence, so a trajectory read {@code ROUTE_TARGET_SELECTED,
 * SUPERCRUISE_JUMP_STARTED, ROUTE_TARGET_SELECTED} where the Commander had
 * selected one target once. This is repeated equivalent journal state, not a
 * documented Frontier defect and not something to reason about further; the
 * graph records what changed, and a restatement changed nothing.</p>
 *
 * <p>The comparison is of state, never of time. Two equal targets are the same
 * selection however far apart they arrive, and two different targets are two
 * selections however close together — so the candidate is compared with the
 * last route-target occurrence of this episode rather than with whatever
 * happened to arrive immediately before it.</p>
 *
 * <p>What identifies a target:</p>
 * <ul>
 *   <li>{@code SystemAddress} when both records carry one. It is Frontier's
 *       stable key, so a display name that differs in case or localisation
 *       does not make a second selection out of one.</li>
 *   <li>the normalised {@code Name} when neither carries an address — the best
 *       identity left, compared without regard to case and without any fuzzy
 *       matching.</li>
 *   <li>nothing, when one record carries an address and the other does not. The
 *       new record establishes a stable identity the earlier one lacked, and an
 *       occurrence already written cannot be enriched in place, so the
 *       observation is preserved as its own occurrence rather than discarded
 *       against a weaker match.</li>
 * </ul>
 *
 * <p>{@code RemainingJumpsInRoute} is part of the comparison: the same system
 * with one fewer jump left is a real route-state update. Absent on both sides
 * counts as equal; absent against a concrete value does not, because learning
 * the route length is not a restatement of it.</p>
 *
 * <p>{@code StarClass} is target metadata and identifies nothing. A corrected
 * star class for the same address and the same route position is not a new
 * selection, and does not by itself admit an occurrence.</p>
 */
public final class RouteTargetSelectionPolicy {

    private static final String SYSTEM_ADDRESS = "SystemAddress";
    private static final String NAME = "Name";
    private static final String REMAINING_JUMPS = "RemainingJumpsInRoute";

    /**
     * Whether this record should become a structural occurrence.
     *
     * @param previousSelection the last route-target occurrence of the active
     *                          episode, or null when this episode has none
     * @param candidate         the normalized record being admitted
     */
    public boolean shouldRecord(
            EventOccurrence previousSelection,
            NormalizedBehaviorEvent candidate
    ) {
        Objects.requireNonNull(candidate, "candidate");
        if (!NormalizedEventType.FSD_TARGET_SELECTED
                .equals(candidate.eventType())) {
            return true;
        }
        if (previousSelection == null
                || !previousSelection.eventType()
                        .equals(candidate.eventType())) {
            return true;
        }
        Map<String, JsonNode> previous = previousSelection.attributes();
        Map<String, JsonNode> current = candidate.attributes();
        return !sameTarget(previous, current)
                || !sameRoutePosition(previous, current);
    }

    private static boolean sameTarget(
            Map<String, JsonNode> previous,
            Map<String, JsonNode> current
    ) {
        Long previousAddress = address(previous);
        Long currentAddress = address(current);
        if (previousAddress != null && currentAddress != null) {
            return previousAddress.equals(currentAddress);
        }
        if (previousAddress != null || currentAddress != null) {
            // One side names a stable system and the other does not. Treating
            // them as one target would settle the question with the weaker of
            // the two identities; see the class comment.
            return false;
        }
        String previousName = name(previous);
        String currentName = name(current);
        return previousName != null && previousName.equals(currentName);
    }

    private static boolean sameRoutePosition(
            Map<String, JsonNode> previous,
            Map<String, JsonNode> current
    ) {
        Long previousJumps = jumps(previous);
        Long currentJumps = jumps(current);
        return Objects.equals(previousJumps, currentJumps);
    }

    private static Long address(Map<String, JsonNode> attributes) {
        JsonNode value = attributes.get(SYSTEM_ADDRESS);
        return value != null && value.canConvertToLong() && value.longValue() > 0
                ? value.longValue()
                : null;
    }

    private static String name(Map<String, JsonNode> attributes) {
        JsonNode value = attributes.get(NAME);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.textValue().strip();
        return text.isEmpty() ? null : text.toUpperCase(Locale.ROOT);
    }

    private static Long jumps(Map<String, JsonNode> attributes) {
        JsonNode value = attributes.get(REMAINING_JUMPS);
        return value != null && value.canConvertToLong()
                ? value.longValue()
                : null;
    }
}
