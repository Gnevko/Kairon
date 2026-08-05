package kairon.observation.journal.event.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static kairon.observation.journal.LlmPresentableJournalEvent.decimal;
import static kairon.observation.journal.LlmPresentableJournalEvent.displayText;
import static kairon.observation.journal.LlmPresentableJournalEvent.quoted;
import static kairon.observation.journal.LlmPresentableJournalEvent.textual;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
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

    BigDecimal KELVIN_OFFSET = new BigDecimal("273.15");
    BigDecimal STANDARD_GRAVITY = new BigDecimal("9.80665");
    BigDecimal METRES_PER_KILOMETRE = new BigDecimal("1000");
    BigDecimal PASCALS_PER_KILOPASCAL = new BigDecimal("1000");
    BigDecimal FRACTION_TO_PERCENT = new BigDecimal("100");

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

    // ----------------------------------------------------------- presentation

    /**
     * One presentation for both variants, deliberately.
     *
     * <p>What differs between the two is the assertion the record makes, which
     * is {@link #modelFacingDescription()}. The facts themselves are the
     * record's own fields, read the same way whichever assertion this is, and
     * an override per variant would be the same body written twice.</p>
     */
    @Override
    default LlmEventPresentation llmPresentation() {
        JsonNode event = raw().parsedJsonObject();
        List<String> sentences = new ArrayList<>();
        sentences.add(scanIdentity(event));
        classification(event).ifPresent(sentences::add);
        physicalProperties(event).ifPresent(sentences::add);
        atmosphereComposition(event).ifPresent(sentences::add);
        bodyComposition(event).ifPresent(sentences::add);
        scanFlags(event).ifPresent(sentences::add);
        materialOccurrences(event).ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
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

    private static String scanIdentity(JsonNode event) {
        String scanDescription = textual(event.get("ScanType"))
                .map(Scan::describeScanType)
                .orElse("a discovery scan");
        String bodyKind = event.hasNonNull("StarType")
                ? "star"
                : event.hasNonNull("PlanetClass")
                        ? "planet or moon"
                        : "celestial body";

        StringBuilder sentence = new StringBuilder("The journal recorded ")
                .append(scanDescription)
                .append(" of the ")
                .append(bodyKind);
        displayText(event, "BodyName")
                .ifPresent(name -> sentence.append(' ').append(quoted(name)));
        integral(event.get("BodyID"))
                .ifPresent(bodyId -> sentence
                        .append(", body ID ")
                        .append(bodyId));
        displayText(event, "StarSystem")
                .ifPresent(system -> sentence
                        .append(", in system ")
                        .append(quoted(system)));
        sentence.append('.');
        return sentence.toString();
    }

    private static Optional<String> classification(JsonNode event) {
        List<String> facts = new ArrayList<>();
        displayText(event, "PlanetClass")
                .ifPresent(value -> facts.add(
                        "planet or moon class " + quoted(value)
                ));
        displayText(event, "StarType")
                .ifPresent(value -> facts.add(
                        "stellar classification " + quoted(value)
                ));
        integral(event.get("Subclass"))
                .ifPresent(value -> facts.add("stellar heat subclass " + value));
        decimal(event.get("StellarMass"))
                .ifPresent(value -> facts.add(
                        "stellar mass " + value + " times the Sun's mass"
                ));
        decimal(event.get("Age_MY"))
                .ifPresent(value -> facts.add(
                        "stellar age " + value + " million years"
                ));
        displayText(event, "TerraformState")
                .ifPresent(value -> facts.add(
                        "terraform state " + quoted(value)
                ));
        displayText(event, "Atmosphere")
                .ifPresent(value -> facts.add(
                        "atmosphere " + quoted(value)
                ));
        displayText(event, "Volcanism")
                .ifPresent(value -> facts.add(
                        "volcanism " + quoted(value)
                ));
        booleanFact(event, "Landable", "landable")
                .ifPresent(facts::add);
        booleanFact(event, "TidalLock", "tidally locked")
                .ifPresent(facts::add);
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The scan reports " + joinFacts(facts) + "."
        );
    }

    private static Optional<String> physicalProperties(JsonNode event) {
        List<String> facts = new ArrayList<>();
        nonNegativeDecimal(event.get("SurfaceGravity"))
                .ifPresent(value -> facts.add(
                        "surface gravity "
                                + plain(value)
                                + " metres per second squared ("
                                + rounded(
                                        value.divide(
                                                STANDARD_GRAVITY,
                                                8,
                                                RoundingMode.HALF_UP
                                        ),
                                        5
                                )
                                + " g)"
                ));
        nonNegativeDecimal(event.get("SurfaceTemperature"))
                .ifPresent(value -> facts.add(
                        "surface temperature "
                                + plain(value)
                                + " kelvins ("
                                + rounded(value.subtract(KELVIN_OFFSET), 3)
                                + " degrees Celsius)"
                ));
        nonNegativeDecimal(event.get("SurfacePressure"))
                .ifPresent(value -> facts.add(
                        "surface pressure "
                                + plain(value)
                                + " pascals ("
                                + rounded(
                                        value.divide(
                                                PASCALS_PER_KILOPASCAL,
                                                6,
                                                RoundingMode.HALF_UP
                                        ),
                                        3
                                )
                                + " kilopascals)"
                ));
        nonNegativeDecimal(event.get("MassEM"))
                .ifPresent(value -> facts.add(
                        "mass " + plain(value) + " Earth masses"
                ));
        nonNegativeDecimal(event.get("Radius"))
                .ifPresent(value -> facts.add(
                        "radius "
                                + rounded(
                                        value.divide(
                                                METRES_PER_KILOMETRE,
                                                6,
                                                RoundingMode.HALF_UP
                                        ),
                                        3
                                )
                                + " kilometres"
                ));
        nonNegativeDecimal(event.get("DistanceFromArrivalLS"))
                .ifPresent(value -> facts.add(
                        "distance from the arrival point "
                                + plain(value)
                                + " light-seconds"
                ));
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The scan's physical measurements report "
                        + joinFacts(facts)
                        + "."
        );
    }

    private static Optional<String> atmosphereComposition(JsonNode event) {
        JsonNode composition = event.get("AtmosphereComposition");
        if (composition == null || !composition.isArray()) {
            return Optional.empty();
        }
        List<String> components = new ArrayList<>();
        for (JsonNode component : composition) {
            Optional<String> name = displayText(component, "Name");
            Optional<BigDecimal> percentage =
                    nonNegativeDecimal(component.get("Percent"))
                            .filter(Scan::isPercentage);
            if (name.isPresent() && percentage.isPresent()) {
                components.add(
                        quoted(name.orElseThrow())
                                + " at "
                                + plain(percentage.orElseThrow())
                                + "%"
                );
            }
        }
        if (components.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The reported atmospheric composition is "
                        + joinFacts(components)
                        + "."
        );
    }

    private static Optional<String> bodyComposition(JsonNode event) {
        JsonNode composition = event.get("Composition");
        if (composition == null || !composition.isObject()) {
            return Optional.empty();
        }
        List<String> components = new ArrayList<>();
        addBodyComposition(components, composition, "Ice", "ice");
        addBodyComposition(components, composition, "Rock", "rock");
        addBodyComposition(components, composition, "Metal", "metal");
        if (components.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The body's bulk composition is "
                        + joinFacts(components)
                        + "."
        );
    }

    private static void addBodyComposition(
            List<String> components,
            JsonNode composition,
            String field,
            String label
    ) {
        nonNegativeDecimal(composition.get(field))
                .filter(value -> value.compareTo(BigDecimal.ONE) <= 0)
                .ifPresent(value -> components.add(
                        label
                                + " "
                                + rounded(
                                        value.multiply(FRACTION_TO_PERCENT),
                                        4
                                )
                                + "%"
                ));
    }

    private static Optional<String> scanFlags(JsonNode event) {
        List<String> facts = new ArrayList<>();
        booleanValue(event.get("WasDiscovered"))
                .ifPresent(value -> facts.add(
                        value
                                ? "had already been discovered"
                                : "had not been discovered"
                ));
        booleanValue(event.get("WasMapped"))
                .ifPresent(value -> facts.add(
                        value
                                ? "had already been mapped"
                                : "had not been mapped"
                ));
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The journal indicates that, before this scan, the body "
                        + joinFacts(facts)
                        + "."
        );
    }

    private static Optional<String> materialOccurrences(JsonNode event) {
        JsonNode materials = event.get("Materials");
        if (materials == null || !materials.isArray()) {
            return Optional.empty();
        }
        List<String> occurrences = new ArrayList<>();
        for (JsonNode material : materials) {
            Optional<String> name = displayText(material, "Name");
            Optional<String> percentage = decimal(material.get("Percent"))
                    .filter(Scan::isPercentage);
            if (name.isPresent() && percentage.isPresent()) {
                occurrences.add(
                        quoted(name.orElseThrow())
                                + " at "
                                + percentage.orElseThrow()
                                + "%"
                );
            }
        }
        if (occurrences.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The scan reports these material occurrence percentages: "
                        + String.join(", ", occurrences)
                        + "."
        );
    }

    private static String describeScanType(String scanType) {
        return switch (scanType) {
            case "Basic" -> "a basic discovery scan";
            case "Detailed" -> "a detailed discovery scan";
            case "NavBeacon" -> "a navigation-beacon scan";
            case "NavBeaconDetail" ->
                    "a detailed navigation-beacon scan";
            case "AutoScan" -> "an automatic discovery scan";
            default -> "a scan whose ScanType is " + quoted(scanType);
        };
    }

    private static Optional<String> booleanFact(
            JsonNode event,
            String fieldName,
            String trueDescription
    ) {
        return booleanValue(event.get(fieldName))
                .map(value -> value
                        ? "the body is marked " + trueDescription
                        : "the body is marked not " + trueDescription);
    }

    private static Optional<Boolean> booleanValue(JsonNode value) {
        return value != null && value.isBoolean()
                ? Optional.of(value.booleanValue())
                : Optional.empty();
    }

    private static Optional<Long> integral(JsonNode value) {
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToLong()
                && value.longValue() >= 0
                ? Optional.of(value.longValue())
                : Optional.empty();
    }

    private static boolean isPercentage(String value) {
        return isPercentage(new BigDecimal(value));
    }

    private static boolean isPercentage(BigDecimal percentage) {
        return percentage.signum() >= 0
                && percentage.compareTo(BigDecimal.valueOf(100)) <= 0;
    }

    private static Optional<BigDecimal> nonNegativeDecimal(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return Optional.empty();
        }
        BigDecimal decimalValue = value.decimalValue();
        return decimalValue.signum() < 0
                ? Optional.empty()
                : Optional.of(decimalValue);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String rounded(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String joinFacts(List<String> facts) {
        if (facts.size() == 1) {
            return facts.getFirst();
        }
        if (facts.size() == 2) {
            return facts.getFirst() + " and " + facts.getLast();
        }
        return String.join(", ", facts.subList(0, facts.size() - 1))
                + ", and "
                + facts.getLast();
    }
}
