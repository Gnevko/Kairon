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
import java.util.Optional;

import static kairon.observation.journal.LlmPresentableJournalEvent.decimal;
import static kairon.observation.journal.LlmPresentableJournalEvent.displayText;
import static kairon.observation.journal.LlmPresentableJournalEvent.quoted;
import static kairon.observation.journal.LlmPresentableJournalEvent.textual;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code Scan} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.3</a>
 */
public record Scan(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Scan";

    private static final BigDecimal KELVIN_OFFSET =
            new BigDecimal("273.15");
    private static final BigDecimal STANDARD_GRAVITY =
            new BigDecimal("9.80665");
    private static final BigDecimal METRES_PER_KILOMETRE =
            new BigDecimal("1000");
    private static final BigDecimal PASCALS_PER_KILOPASCAL =
            new BigDecimal("1000");
    private static final BigDecimal FRACTION_TO_PERCENT =
            new BigDecimal("100");

    public Scan {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
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
        java.math.BigDecimal percentage = new java.math.BigDecimal(value);
        return isPercentage(percentage);
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
