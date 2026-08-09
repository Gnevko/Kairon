package kairon.bio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reads {@code organic-registry.json}, strictly.
 *
 * <p>Every failure is a startup failure with the exact path of what was wrong.
 * A registry half-read is worse than none: the missing half is invisible at
 * runtime, and every name it would have supplied silently falls back to
 * whatever the journal happened to say. So a duplicate identifier, a species
 * pointing at a genus that is not declared, a blank name — each of them stops
 * the application rather than shrinking the lookup.</p>
 *
 * <p>Unknown properties are ignored. The file carries {@code valueCr} and
 * {@code colonyDistanceM} that nothing reads yet, and the point of a document
 * format is that adding a fact to it is not a breaking change.</p>
 */
public final class JsonOrganicRegistryLoader {

    /** The one schema this loader accepts. There is no second version. */
    public static final String SCHEMA = "kairon-organic-registry-v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonOrganicRegistryLoader() {
    }

    /** Raised when the file cannot be read or does not say what it must. */
    public static final class OrganicRegistryException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        OrganicRegistryException(String message) {
            super(message);
        }

        OrganicRegistryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The registry in {@code file}.
     *
     * @throws OrganicRegistryException if the file cannot be read, is not this
     *                                  schema, or contradicts itself
     */
    public static OrganicRegistry load(Path file) {
        Objects.requireNonNull(file, "file");
        JsonNode document;
        try {
            document = MAPPER.readTree(Files.readString(file));
        } catch (IOException unreadable) {
            throw new OrganicRegistryException(
                    "organic registry " + file + " could not be read",
                    unreadable
            );
        }
        if (document == null || !document.isObject()) {
            throw new OrganicRegistryException(
                    "organic registry " + file + " is not a JSON object"
            );
        }
        JsonNode schema = document.get("schema");
        if (schema == null || !SCHEMA.equals(schema.asText(null))) {
            throw new OrganicRegistryException(
                    "organic registry " + file + " declares schema "
                            + (schema == null ? "nothing" : schema.asText(null))
                            + ", expected " + SCHEMA
            );
        }

        Map<String, Map<String, String>> names = new TreeMap<>();
        Map<String, Long> values = new TreeMap<>();
        Map<String, String> parents = new LinkedHashMap<>();
        Map<String, List<OrganicRuleset>> rulesets = new LinkedHashMap<>();
        read(file, document, "genera", null, names, values, parents, rulesets);
        read(file, document, "species", "genus", names, values, parents, rulesets);
        read(file, document, "variants", "species", names, values, parents, rulesets);

        parents.forEach((identifier, parent) -> {
            if (!names.containsKey(parent)) {
                throw new OrganicRegistryException(
                        "organic registry " + file + ": " + identifier
                                + " belongs to " + parent
                                + ", which the file does not declare"
                );
            }
        });
        return new OrganicRegistry(names, values, parents, rulesets);
    }

    private static void read(
            Path file,
            JsonNode document,
            String section,
            String parentProperty,
            Map<String, Map<String, String>> names,
            Map<String, Long> values,
            Map<String, String> parents,
            Map<String, List<OrganicRuleset>> rulesets
    ) {
        JsonNode entries = document.get(section);
        if (entries == null || !entries.isArray()) {
            throw new OrganicRegistryException(
                    "organic registry " + file + " has no " + section + " array"
            );
        }
        int index = 0;
        for (JsonNode entry : entries) {
            String where = section + "[" + index++ + "]";
            String identifier = requiredText(file, entry, "id", where);
            if (names.putIfAbsent(identifier, readNames(file, entry, where)) != null) {
                throw new OrganicRegistryException(
                        "organic registry " + file + ": " + identifier
                                + " is declared twice"
                );
            }
            if (parentProperty != null) {
                parents.put(
                        identifier,
                        requiredText(file, entry, parentProperty, where)
                );
            }
            JsonNode value = entry.get("valueCr");
            if (value != null && !value.isNull()) {
                if (!value.canConvertToLong() || value.longValue() <= 0) {
                    throw new OrganicRegistryException(
                            "organic registry " + file + ": " + where
                                    + ".valueCr is not a positive number"
                    );
                }
                values.put(identifier, value.longValue());
            }
            List<OrganicRuleset> rules = readRulesets(file, entry, where);
            if (!rules.isEmpty()) {
                rulesets.put(identifier, rules);
            }
        }
    }

    /**
     * The spawn rules of one entry, which most entries do not have.
     *
     * <p>Absent is the normal case and means the file says nothing about where
     * this organism grows. Present and malformed is a generator fault and stops
     * the application, on the same principle as everything else here: a rule
     * read as something other than what it says would narrow a genus wrongly,
     * and a wrongly narrowed genus is a wrong number spoken with confidence.</p>
     */
    private static List<OrganicRuleset> readRulesets(
            Path file,
            JsonNode entry,
            String where
    ) {
        JsonNode listed = entry.get("rulesets");
        if (listed == null || listed.isNull()) {
            return List.of();
        }
        if (!listed.isArray()) {
            throw new OrganicRegistryException(
                    "organic registry " + file + ": " + where
                            + ".rulesets is not an array"
            );
        }
        List<OrganicRuleset> rules = new ArrayList<>();
        int index = 0;
        for (JsonNode rule : listed) {
            String at = where + ".rulesets[" + index++ + "]";
            if (!rule.isObject()) {
                throw new OrganicRegistryException(
                        "organic registry " + file + ": " + at + " is not an object"
                );
            }
            rules.add(new OrganicRuleset(
                    strings(file, rule, "bodyTypes", at),
                    strings(file, rule, "atmospheres", at),
                    number(file, rule, "minGravity", at),
                    number(file, rule, "maxGravity", at),
                    number(file, rule, "minTemperature", at),
                    number(file, rule, "maxTemperature", at),
                    number(file, rule, "minPressure", at),
                    number(file, rule, "maxPressure", at),
                    volcanism(file, rule, at)
            ));
        }
        return List.copyOf(rules);
    }

    /**
     * What a ruleset demands of volcanism, in the upstream's own three shapes.
     *
     * <p>{@code "None"}, {@code "Any"} and a list of fragments. Anything else
     * is refused rather than guessed at — a fourth shape appearing upstream is
     * exactly the thing that must not be read as "no demand".</p>
     */
    private static OrganicRuleset.Volcanism volcanism(
            Path file,
            JsonNode rule,
            String at
    ) {
        JsonNode stated = rule.get("volcanism");
        if (stated == null || stated.isNull()) {
            return OrganicRuleset.Volcanism.UNCONSTRAINED;
        }
        if (stated.isTextual()) {
            String text = stated.asText();
            if ("None".equals(text)) {
                return OrganicRuleset.Volcanism.ABSENT;
            }
            if ("Any".equals(text)) {
                return OrganicRuleset.Volcanism.PRESENT;
            }
            return OrganicRuleset.Volcanism.containingOneOf(List.of(text));
        }
        if (stated.isArray()) {
            return OrganicRuleset.Volcanism.containingOneOf(
                    strings(file, rule, "volcanism", at)
            );
        }
        throw new OrganicRegistryException(
                "organic registry " + file + ": " + at
                        + ".volcanism is neither a string nor an array"
        );
    }

    private static List<String> strings(
            Path file,
            JsonNode rule,
            String property,
            String at
    ) {
        JsonNode listed = rule.get(property);
        if (listed == null || listed.isNull()) {
            return List.of();
        }
        if (!listed.isArray()) {
            throw new OrganicRegistryException(
                    "organic registry " + file + ": " + at + "." + property
                            + " is not an array"
            );
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : listed) {
            String text = element.asText(null);
            if (text == null || text.isBlank()) {
                throw new OrganicRegistryException(
                        "organic registry " + file + ": " + at + "." + property
                                + " has a blank element"
                );
            }
            values.add(text);
        }
        return List.copyOf(values);
    }

    private static Double number(
            Path file,
            JsonNode rule,
            String property,
            String at
    ) {
        JsonNode stated = rule.get(property);
        if (stated == null || stated.isNull()) {
            return null;
        }
        if (!stated.isNumber()) {
            throw new OrganicRegistryException(
                    "organic registry " + file + ": " + at + "." + property
                            + " is not a number"
            );
        }
        return stated.doubleValue();
    }

    /**
     * The names of one entry, which may be none.
     *
     * <p>An entry with no name at all is kept rather than rejected: it says the
     * organism exists and that the file has no word for it, which is exactly
     * what a genus known only from a journal reading looks like. A name that is
     * present and blank is a different thing — that is a generator fault.</p>
     */
    private static Map<String, String> readNames(
            Path file,
            JsonNode entry,
            String where
    ) {
        JsonNode names = entry.get("names");
        if (names == null || names.isNull()) {
            return Map.of();
        }
        if (!names.isObject()) {
            throw new OrganicRegistryException(
                    "organic registry " + file + ": " + where
                            + ".names is not an object"
            );
        }
        Map<String, String> byLanguage = new TreeMap<>();
        names.properties().forEach(property -> {
            String name = property.getValue().asText(null);
            if (name == null || name.isBlank()) {
                throw new OrganicRegistryException(
                        "organic registry " + file + ": " + where + ".names."
                                + property.getKey() + " is blank"
                );
            }
            byLanguage.put(property.getKey(), name);
        });
        return byLanguage;
    }

    private static String requiredText(
            Path file,
            JsonNode entry,
            String property,
            String where
    ) {
        String value = entry == null ? null : entry.path(property).asText(null);
        if (value == null || value.isBlank()) {
            throw new OrganicRegistryException(
                    "organic registry " + file + ": " + where + "." + property
                            + " is missing"
            );
        }
        return value;
    }
}
