package kairon.semantics;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.event.exploration.Scan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a scanner established about one body, read once and read the same way.
 *
 * <p>Three consumers ask the same questions of a {@code Scan}, an
 * {@code FSSBodySignals} and an {@code SAASignalsFound}: which body is this,
 * is there a result here at all, and is it the result we already had. The
 * behaviour graph asks so it does not record the same reading twice, the
 * observer asks so it does not open a turn about it twice, and the semantic
 * adapters ask so the model is told what was found. Each of those owns its own
 * memory of what it has seen; none of them owns the rule, which lives here and
 * is pure.</p>
 *
 * <p>Nothing here decides importance. Being new is not being interesting — it
 * is only the difference between one reading and two.</p>
 */
public final class BodySurveyFacts {

    /** The one scan depth that establishes a body rather than listing it. */
    public static final String DETAILED_SCAN = "DETAILED";

    /** Signal categories the contract names outright. */
    public static final String BIOLOGICAL = "BIOLOGICAL";
    public static final String GEOLOGICAL = "GEOLOGICAL";
    public static final String HUMAN = "HUMAN";
    public static final String THARGOID = "THARGOID";

    /** Everything else, said as a category rather than as a game symbol. */
    public static final String OTHER = "OTHER";

    private static final String SIGNAL_TYPE_PREFIX = "$SAA_SignalType_";
    private static final Map<String, Integer> MODEL_FACING_ORDER = Map.of(
            BIOLOGICAL, 0,
            GEOLOGICAL, 1,
            HUMAN, 2,
            THARGOID, 3,
            OTHER, 4
    );

    private BodySurveyFacts() {
    }

    /**
     * Whether this scan is the detailed one.
     *
     * <p>An {@code AutoScan} is the ship noticing a body it flew past and a
     * {@code Basic} scan is a name and a distance. Only {@code Detailed}
     * carries the classification, the flags and the measurements, and only it
     * is the result of something the Commander did. Compared case
     * insensitively, and an absent or unrecognised depth is not detailed.</p>
     */
    public static boolean detailedScan(JsonNode raw) {
        return DETAILED_SCAN.equals(scanDepth(raw));
    }

    /** The scan depth as a closed token, or null when the record omits it. */
    public static String scanDepth(JsonNode raw) {
        String depth = text(raw, "ScanType");
        return depth.isEmpty() ? null : depth.toUpperCase(Locale.ROOT);
    }

    /**
     * Which body this record is about, or null when it does not say.
     *
     * <p>Both halves are required. A body name is what a comment says out loud
     * and never what one body is told from another by: two systems name their
     * moons alike, and a reading filed under the wrong body is worse than a
     * reading dropped.</p>
     */
    public static BodyIdentity bodyIdentity(JsonNode raw) {
        Long systemAddress = nonNegativeLong(raw, "SystemAddress");
        Long bodyId = nonNegativeLong(raw, "BodyID");
        return systemAddress == null || bodyId == null
                ? null
                : new BodyIdentity(systemAddress, bodyId);
    }

    /**
     * What this detailed scan established, as one comparable string.
     *
     * <p>Null when there is nothing to compare: not a detailed scan, or no
     * body to file it under. Otherwise every model-relevant fact of the
     * reading, in a fixed order, so that two scans compare equal exactly when
     * they say the same thing about the same body. Deliberately not the raw
     * JSON: field order, timestamps and the measurements the model never sees
     * would all make an identical reading look new.</p>
     *
     * <p>What the body <em>is</em>, and not where it happens to be. Completing
     * a surface survey makes the game re-emit the whole scan record, identical
     * in every classification and flag — the survey itself is reported by
     * {@code SAAScanComplete} and the signals by their own record. The one
     * thing that had moved was {@code DistanceFromArrivalLS}: a body drifts
     * along its orbit between two readings, so a position compared as identity
     * makes every re-emitted record a second finding, opening a turn about a
     * scan nobody took and giving the graph an occurrence and a transition that
     * never happened. A position is not a property, and no two readings of one
     * body taken at different times share one. Orbital measurements are out for
     * the same reason; the current distance still reaches the model through the
     * event and the body context, where it is a fact rather than an identity.
     * </p>
     */
    public static String scanSignature(JsonNode raw) {
        if (!detailedScan(raw)) {
            return null;
        }
        BodyIdentity key = bodyIdentity(raw);
        if (key == null) {
            return null;
        }
        return String.join(
                "|",
                Long.toString(key.systemAddress()),
                Long.toString(key.bodyId()),
                DETAILED_SCAN,
                bodyKind(raw) == null ? "" : bodyKind(raw),
                text(raw, "StarType"),
                text(raw, "PlanetClass"),
                flag(raw, "Landable"),
                text(raw, "TerraformState"),
                text(raw, "Atmosphere"),
                text(raw, "Volcanism"),
                flag(raw, "WasDiscovered"),
                flag(raw, "WasMapped"),
                flag(raw, "WasFootfalled")
        );
    }

    /**
     * Whether this scan is a star reading that reports no prior discovery.
     *
     * <p>Shape only, and deliberately not "is this the arrival star". The
     * record cannot say which body a system was entered at; what it can say is
     * that it is a shallower-than-detailed reading of a star, filed under a
     * body, whose {@code WasDiscovered} is explicitly false. Who decides
     * whether that star is the one this visit arrived at is the visit's owner —
     * the observer's own memory of the arrival, and the graph's episode root.
     * Both ask the same question of the same fields here, so neither can admit
     * a reading the other refuses.</p>
     *
     * <p>Detailed scans are excluded because they already establish the body in
     * full and reach the model as a scan result. An absent or true
     * {@code WasDiscovered} establishes nothing: the flag is a claim the record
     * either makes or does not, and a missing one is silence rather than a
     * denial.</p>
     *
     * <p>The reading itself lives on {@link Scan}, because the record is what
     * the shape belongs to and because the record has to answer the same
     * question when it describes itself. This delegates rather than repeating
     * it: two copies of a predicate that decides what a turn is about would
     * drift, and the drift would be a scan reported as a milestone or the
     * reverse.</p>
     */
    public static boolean undiscoveredStarReading(JsonNode raw) {
        return Scan.reportsUndiscoveredStar(raw);
    }

    /**
     * Whether this scan reports a star or a planet, or null when it says
     * neither.
     *
     * <p>Read from which classification the record supplied rather than
     * guessed: a star scan carries {@code StarType} and a planet or moon scan
     * carries {@code PlanetClass}, and the two never appear together.</p>
     */
    public static String bodyKind(JsonNode raw) {
        if (!text(raw, "StarType").isEmpty()) {
            return "STAR";
        }
        return text(raw, "PlanetClass").isEmpty() ? null : "PLANET";
    }

    /**
     * The reported signal set for one body, as one comparable string.
     *
     * <p>Built from {@link #normalizedSignalCounts}, so the signature, the
     * canonical merge, the graph's deduplication and the observer's novelty
     * memory all compare the same set. Null when there is nothing to compare:
     * no body, or nothing positive reported. Two readings of the same body
     * compare equal exactly when they report the same categories at the same
     * counts, whichever scanner reported them — a surface survey confirming
     * what the system scan already said is the same fact told twice, not a
     * second finding.</p>
     */
    public static String signalSignature(JsonNode raw) {
        BodyIdentity key = bodyIdentity(raw);
        Map<String, Integer> counts = normalizedSignalCounts(raw);
        if (key == null || counts.isEmpty()) {
            return null;
        }
        StringBuilder signature = new StringBuilder()
                .append(key.systemAddress())
                .append('|')
                .append(key.bodyId());
        counts.forEach((type, count) -> signature
                .append('|')
                .append(type)
                .append('=')
                .append(count));
        return signature.toString();
    }

    /**
     * What this reading positively established, keyed by the category the game
     * named.
     *
     * <p>The one definition of a normalized signal set. Sorted, and only counts
     * above zero survive: a category reported at zero, or below it, is not
     * evidence that a signal previously counted there is gone. The game says a
     * signal is present by counting it and says nothing at all by any other
     * means, so a zero retracts nothing, clears nothing and establishes
     * nothing. Retracting a known count would need a source that actually
     * asserts absence, and no such source exists.</p>
     *
     * <p>Categories outside the closed set keep their own derived key here so
     * two different unknown categories never merge into one canonical count;
     * only the model-facing rendering folds them into {@code OTHER}.</p>
     */
    public static Map<String, Integer> normalizedSignalCounts(JsonNode raw) {
        JsonNode signals = raw == null ? null : raw.get("Signals");
        if (signals == null || !signals.isArray()) {
            return Map.of();
        }
        Map<String, Integer> counts = new TreeMap<>();
        for (JsonNode signal : signals) {
            String type = normalizedSignalType(text(signal, "Type"));
            Integer count = positiveCount(signal);
            if (type == null || count == null) {
                continue;
            }
            counts.merge(type, count, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    /**
     * The reported categories as the model sees them, or unknown when there
     * are none.
     *
     * <p>Order is fixed rather than as-reported: the same reading always
     * serializes identically, and the categories a Commander asks about first
     * come first.</p>
     */
    public static SemanticValue signals(JsonNode raw) {
        JsonNode signals = raw == null ? null : raw.get("Signals");
        if (signals == null || !signals.isArray()) {
            return SemanticValue.unknown();
        }
        Map<String, ReportedSignal> merged = new LinkedHashMap<>();
        for (JsonNode signal : signals) {
            String derived = normalizedSignalType(text(signal, "Type"));
            Integer count = positiveCount(signal);
            if (derived == null || count == null) {
                continue;
            }
            String modelFacing = MODEL_FACING_ORDER.containsKey(derived)
                    ? derived
                    : OTHER;
            String label = OTHER.equals(modelFacing)
                    ? displayLabel(signal)
                    : null;
            merged.merge(
                    modelFacing + ' ' + (label == null ? "" : label),
                    new ReportedSignal(modelFacing, label, count),
                    ReportedSignal::plus
            );
        }
        if (merged.isEmpty()) {
            return SemanticValue.unknown();
        }
        List<ReportedSignal> ordered = new ArrayList<>(merged.values());
        ordered.sort(
                Comparator.comparingInt(
                                (ReportedSignal reported) ->
                                        MODEL_FACING_ORDER.get(reported.type())
                        )
                        .thenComparing(
                                ReportedSignal::label,
                                Comparator.nullsLast(
                                        Comparator.naturalOrder()
                                )
                        )
        );
        List<SemanticValue.SignalCountsValue.SignalCount> counts =
                new ArrayList<>(ordered.size());
        for (ReportedSignal reported : ordered) {
            counts.add(new SemanticValue.SignalCountsValue.SignalCount(
                    reported.type(),
                    reported.label(),
                    reported.count()
            ));
        }
        return new SemanticValue.SignalCountsValue(List.copyOf(counts));
    }

    /**
     * The category behind a game signal token, or null when there is none.
     *
     * <p>{@code $SAA_SignalType_Geological;} is the game's own identifier and
     * never reaches the model. What survives is the word inside it.</p>
     */
    public static String normalizedSignalType(String rawType) {
        if (rawType == null) {
            return null;
        }
        String token = rawType.strip();
        if (token.startsWith(SIGNAL_TYPE_PREFIX)) {
            token = token.substring(SIGNAL_TYPE_PREFIX.length());
        }
        if (token.endsWith(";")) {
            token = token.substring(0, token.length() - 1);
        }
        token = token.strip().toUpperCase(Locale.ROOT);
        return token.isEmpty() ? null : token;
    }

    private static String displayLabel(JsonNode signal) {
        String localised = text(signal, "Type_Localised");
        if (!localised.isEmpty()) {
            return localised;
        }
        String plain = text(signal, "Type");
        return plain.isEmpty() || plain.startsWith("$")
                ? null
                : plain;
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

    private static String flag(JsonNode raw, String name) {
        JsonNode value = raw == null ? null : raw.get(name);
        return value != null && value.isBoolean()
                ? Boolean.toString(value.booleanValue())
                : "";
    }

    private static Long nonNegativeLong(JsonNode raw, String name) {
        JsonNode value = raw == null ? null : raw.get(name);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < 0) {
            return null;
        }
        return value.longValue();
    }

    /**
     * The count this entry reports, or null when it reports none.
     *
     * <p>The single threshold every reader of a signal entry shares. A missing,
     * malformed or non-integral count is no count; so is zero, and so is a
     * negative one.</p>
     */
    private static Integer positiveCount(JsonNode signal) {
        JsonNode value = signal == null ? null : signal.get("Count");
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.intValue() < 1) {
            return null;
        }
        return value.intValue();
    }

    private record ReportedSignal(String type, String label, long count) {

        private ReportedSignal plus(ReportedSignal other) {
            return new ReportedSignal(type, label, count + other.count());
        }
    }
}
