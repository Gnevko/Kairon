package kairon.observation.journal.event.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
 * {@code Scan} journal event.
 *
 * <p>One wire event, two domain events. A reading establishes what a body is;
 * a shallower reading of a star that reports nobody had discovered it is a
 * different assertion made from the same record, and it is the only record that
 * ever carries that fact. The dispatch happens once, here, at parse time.</p>
 *
 * <p>It is the one split whose variants are genuinely different domain events
 * downstream — {@code BODY_SCANNED} against
 * {@code SYSTEM_UNDISCOVERED_CONFIRMED} — and before the split every layer that
 * needed to tell them apart re-read the record to do it: the behaviour
 * normalizer, the decision catalogue through a record-earned rule, and the
 * description through a ternary of its own. Three readings of one predicate,
 * each of which could have been the one that drifted.</p>
 *
 * <p>The sealed interface stays the record as far as everything that asks
 * <em>what kind of journal event is this</em> is concerned — source role,
 * structural significance, the semantic adapter. Those were decided once when
 * the event was researched, and one research answer does not become two because
 * the parser learned to dispatch.</p>
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.3</a>
 */
public sealed interface Scan extends LlmPresentableJournalEvent {

    String EVENT_TYPE = "Scan";
    BigDecimal STANDARD_GRAVITY = new BigDecimal("9.80665");
    /**
     * The domain event this record actually is.
     *
     * <p>The single dispatch, and the only place {@link
     * #reportsUndiscoveredStar} decides anything about a parsed observation.
     * There is no unrecognised arm: the discriminator is not a vocabulary
     * Frontier can extend but a shape the record either has or does not, and
     * every reading that is not the star milestone is a reading of a body.</p>
     */
    static Scan of(RawJournalData raw) {
        JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        return reportsUndiscoveredStar(raw.parsedJsonObject())
                ? new UndiscoveredStar(raw)
                : new BodyReading(raw);
    }

    /**
     * Whether a record is a star reading that reports no prior discovery.
     *
     * <p>Shape only, and deliberately not "is this the arrival star": the
     * record cannot say which body a system was entered at. What it can say is
     * that it is a shallower-than-detailed reading of a star, filed under a
     * body, whose {@code WasDiscovered} is explicitly false. Which visit that
     * star belongs to is decided by the layers that track visits.</p>
     *
     * <p>This is the one implementation, and since the split it is asked of a
     * parsed observation exactly once — by {@link #of}. It stays public because
     * the layers that track visits hold a stored record rather than the typed
     * observation: {@code BodySurveyFacts} delegates to it, so the graph's
     * episode policy and the observer's arrival memory read the same fields the
     * same way as the parser did.</p>
     *
     * <p>An absent or true {@code WasDiscovered} establishes nothing: the flag
     * is a claim the record either makes or does not, and a missing one is
     * silence rather than a denial. Detailed readings are excluded because they
     * establish the body in full and are reported as a scan result.</p>
     */
    static boolean reportsUndiscoveredStar(JsonNode raw) {
        return !"DETAILED".equals(scanDepth(raw))
                && !text(raw, "StarType").isEmpty()
                && isFalse(raw, "WasDiscovered")
                && namesABody(raw);
    }

    /** A reading of what a star, planet or moon is. */
    record BodyReading(RawJournalData raw) implements Scan {

        public BodyReading {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A discovery scan reported a star, planet or moon's "
                    + "properties.";
        }
    }

    /**
     * A star reported as never having been discovered.
     *
     * <p>The sentence does not say which star it is or which visit it belongs
     * to — the record establishes neither, and the named fields beside the
     * sentence carry what it does.</p>
     */
    record UndiscoveredStar(RawJournalData raw) implements Scan {

        public UndiscoveredStar {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A scan reported a star as not previously discovered.";
        }
    }

    private static String scanDepth(JsonNode raw) {
        String depth = text(raw, "ScanType");
        return depth.isEmpty() ? null : depth.toUpperCase(Locale.ROOT);
    }

    private static boolean isFalse(JsonNode raw, String name) {
        JsonNode value = raw == null ? null : raw.get(name);
        return value != null && value.isBoolean() && !value.booleanValue();
    }

    private static boolean namesABody(JsonNode raw) {
        return nonNegative(raw, "SystemAddress") && nonNegative(raw, "BodyID");
    }

    private static boolean nonNegative(JsonNode raw, String name) {
        JsonNode value = raw == null ? null : raw.get(name);
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToLong()
                && value.longValue() >= 0L;
    }

    private static String text(JsonNode node, String name) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(name);
        return value != null && value.isTextual()
                ? value.textValue().strip()
                : "";
    }
    private static Optional<Boolean> booleanValue(JsonNode value) {
        return value != null && value.isBoolean()
                ? Optional.of(value.booleanValue())
                : Optional.empty();
    }
    private static boolean isPercentage(String value) {
        return isPercentage(new BigDecimal(value));
    }

    private static boolean isPercentage(BigDecimal percentage) {
        return percentage.signum() >= 0
                && percentage.compareTo(BigDecimal.valueOf(100)) <= 0;
    }
}
