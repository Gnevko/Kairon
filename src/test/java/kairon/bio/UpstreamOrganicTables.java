package kairon.bio;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The community exobiology tables, read at a pinned upstream revision.
 *
 * <p>Two upstream files answer three questions this project has no other source
 * for: what the game calls a genus and a species, what one sample is worth, and
 * which colour a variant identifier stands for. They are read, never linked,
 * never copied into this repository, and never executed — see
 * {@code THIRD_PARTY_NOTICES.md} for the revisions and the licence.</p>
 *
 * <p>The sources are Python dictionary literals with rigid four-space
 * indentation, so they are read by a small brace-and-indent scanner rather than
 * by anything that pretends to understand Python. The scanner emits every scalar
 * assignment together with the chain of dictionary keys containing it, and this
 * class then picks out the three shapes it knows. A shape that stops appearing
 * is a parse that produces nothing, which the caller asserts on — the failure
 * mode is an empty table, never a half-read one.</p>
 */
final class UpstreamOrganicTables {

    /** Pinned upstream revisions. Changing one is a change to the data. */
    static final String BIO_SCAN_REVISION =
            "5f0d2e445a95681bf2e85223f883d5c552a7726b";
    static final String EXPLO_DATA_REVISION =
            "011ec1d01d17960cb40de6e85139422024f544a8";

    private static final String BIO_SCAN_BASE =
            "https://raw.githubusercontent.com/Silarn/EDMC-BioScan/"
                    + BIO_SCAN_REVISION + "/src/bio_scan/bio_data/";
    private static final String EXPLO_DATA_GENUS =
            "https://raw.githubusercontent.com/Silarn/EDMC-ExploData/"
                    + EXPLO_DATA_REVISION
                    + "/src/ExploData/explo_data/bio_data/genus.py";

    /**
     * The per-genus ruleset modules, plus the module holding the organisms that
     * belong to no genus (bark mounds, amphora plants, radicoida).
     */
    private static final List<String> BIO_SCAN_MODULES = List.of(
            "species.py",
            "rulesets/aleoida.py",
            "rulesets/anemone.py",
            "rulesets/bacterium.py",
            "rulesets/brain_tree.py",
            "rulesets/cactoida.py",
            "rulesets/clypeus.py",
            "rulesets/concha.py",
            "rulesets/electricae.py",
            "rulesets/fonticulua.py",
            "rulesets/frutexa.py",
            "rulesets/fumerola.py",
            "rulesets/fungoida.py",
            "rulesets/osseus.py",
            "rulesets/recepta.py",
            "rulesets/shard.py",
            "rulesets/stratum.py",
            "rulesets/tubers.py",
            "rulesets/tubus.py",
            "rulesets/tussock.py"
    );

    private static final String SYMBOL_PREFIX = "$Codex_Ent_";
    private static final String SYMBOL_SUFFIX = "_Name;";

    private UpstreamOrganicTables() {
    }

    // ------------------------------------------------------------- the tables

    /**
     * What the upstream says about one genus.
     *
     * <p>{@code colours} is keyed by the fragment the game puts in a variant
     * identifier: a star class ({@code A}, {@code TTS}) for most genera, a raw
     * material for the rest. {@code speciesColours} carries the same thing for
     * genera whose species do not share one table.</p>
     */
    record Genus(
            String id,
            String name,
            Integer colonyDistanceM,
            Boolean multipleSpecies,
            Map<String, String> colours,
            Map<String, Map<String, String>> speciesColours
    ) {
        Genus {
            id = Objects.requireNonNull(id, "id");
            colours = Map.copyOf(colours);
            speciesColours = Map.copyOf(speciesColours);
        }

        /** The colour table that applies to one species, possibly empty. */
        Map<String, String> coloursFor(String speciesId) {
            Map<String, String> own = speciesColours.get(speciesId);
            return own != null ? own : colours;
        }
    }

    /** What the upstream says about one species. */
    record Species(
            String id,
            String genusId,
            String name,
            Long valueCr,
            List<Ruleset> rulesets
    ) {
        Species {
            id = Objects.requireNonNull(id, "id");
            genusId = Objects.requireNonNull(genusId, "genusId");
            rulesets = List.copyOf(rulesets == null ? List.of() : rulesets);
        }
    }

    /**
     * One set of conditions under which the upstream says a species grows.
     *
     * <p>Only the six dimensions this project reads. The upstream also
     * constrains by galactic region, star class, materials and what else is in
     * the system; those are dropped here rather than downstream, because a
     * constraint carried into the file and then ignored would look like a
     * constraint that was applied. Dropping them can only widen what a body
     * admits, which is the direction ADR-0030 requires.</p>
     *
     * <p>{@code volcanismWord} is the upstream's {@code None} or {@code Any},
     * and is null when it wrote a list instead — the two are alternatives, and
     * both being empty means the ruleset says nothing about volcanism.</p>
     */
    record Ruleset(
            List<String> bodyTypes,
            List<String> atmospheres,
            Double minGravity,
            Double maxGravity,
            Double minTemperature,
            Double maxTemperature,
            Double minPressure,
            Double maxPressure,
            String volcanismWord,
            List<String> volcanismFragments
    ) {
        Ruleset {
            bodyTypes = List.copyOf(bodyTypes == null ? List.of() : bodyTypes);
            atmospheres = List.copyOf(atmospheres == null ? List.of() : atmospheres);
            volcanismFragments = List.copyOf(
                    volcanismFragments == null ? List.of() : volcanismFragments
            );
        }

        /** Whether it constrains anything this project reads. */
        boolean constrainsSomething() {
            return !bodyTypes.isEmpty()
                    || !atmospheres.isEmpty()
                    || minGravity != null || maxGravity != null
                    || minTemperature != null || maxTemperature != null
                    || minPressure != null || maxPressure != null
                    || volcanismWord != null || !volcanismFragments.isEmpty();
        }
    }

    /** Both tables, keyed by identifier and sorted for a stable diff. */
    record Tables(Map<String, Genus> genera, Map<String, Species> species) {
        Tables {
            genera = Collections.unmodifiableMap(new TreeMap<>(genera));
            species = Collections.unmodifiableMap(new TreeMap<>(species));
        }
    }

    // -------------------------------------------------------------- retrieval

    /**
     * Read both upstream tables.
     *
     * @param localDirectory local copies of the pinned sources, or {@code null}
     *                       to download them. A local file is named after the
     *                       upstream module with its directory flattened, so
     *                       {@code rulesets/bacterium.py} is
     *                       {@code bioscan-bacterium.py} and the ExploData genus
     *                       table is {@code explodata-genus.py}.
     */
    static Tables read(Path localDirectory) throws IOException, InterruptedException {
        Map<String, Genus> genera = readGenusTable(
                source(localDirectory, "explodata-genus.py", EXPLO_DATA_GENUS)
        );
        Map<String, Species> species = new TreeMap<>();
        for (String module : BIO_SCAN_MODULES) {
            String flattened = "bioscan-"
                    + module.substring(module.lastIndexOf('/') + 1);
            species.putAll(readSpeciesTable(
                    source(localDirectory, flattened, BIO_SCAN_BASE + module)
            ));
        }
        return new Tables(genera, species);
    }

    private static String source(Path localDirectory, String localName, String url)
            throws IOException, InterruptedException {
        if (localDirectory != null) {
            return Files.readString(
                    localDirectory.resolve(localName),
                    StandardCharsets.UTF_8
            );
        }
        return download(url);
    }

    private static String download(String url) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(60))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() != 200) {
                throw new IOException(
                        "upstream " + url + " answered " + response.statusCode()
                );
            }
            return response.body();
        }
    }

    // ---------------------------------------------------------------- reading

    /**
     * The ExploData genus table.
     *
     * <p>Leaf shapes, where the file's own outermost dictionary is the unnamed
     * first element of every path:</p>
     * <pre>
     * ["", genus]                                   name / distance / multiple
     * ["", genus, "colors", "star"|"element"]       one colour table per genus
     * ["", genus, "colors", "species", id, "star"]  one colour table per species
     * </pre>
     */
    private static Map<String, Genus> readGenusTable(String source) {
        record Draft(
                Map<String, String> scalars,
                Map<String, String> colours,
                Map<String, Map<String, String>> speciesColours
        ) {
        }
        Map<String, Draft> drafts = new LinkedHashMap<>();
        for (Leaf leaf : scan(source)) {
            List<String> path = leaf.path();
            if (path.size() < 2 || !isSymbol(path.get(1))) {
                continue;
            }
            Draft draft = drafts.computeIfAbsent(
                    path.get(1),
                    id -> new Draft(
                            new LinkedHashMap<>(),
                            new LinkedHashMap<>(),
                            new LinkedHashMap<>()
                    )
            );
            if (path.size() == 2) {
                draft.scalars().put(leaf.key(), leaf.value());
            } else if (path.size() == 4 && "colors".equals(path.get(2))) {
                draft.colours().put(leaf.key(), leaf.value());
            } else if (path.size() == 6
                    && "colors".equals(path.get(2))
                    && "species".equals(path.get(3))
                    && isSymbol(path.get(4))) {
                draft.speciesColours()
                        .computeIfAbsent(path.get(4), id -> new LinkedHashMap<>())
                        .put(leaf.key(), leaf.value());
            }
        }
        Map<String, Genus> genera = new TreeMap<>();
        drafts.forEach((id, draft) -> genera.put(id, new Genus(
                id,
                draft.scalars().get("name"),
                integer(draft.scalars().get("distance")),
                bool(draft.scalars().get("multiple")),
                draft.colours(),
                draft.speciesColours()
        )));
        return genera;
    }

    /**
     * A BioScan catalogue module.
     *
     * <p>Leaf shape {@code [root, genus, species]}, where {@code root} is the
     * module's own dictionary. The spawn rulesets are deeper than that and are
     * read by a second pass, {@link #readRulesetTables}, because they are lists
     * of dictionaries and the leaf scanner deliberately understands neither —
     * it keeps the stack balanced through them and reports their scalars
     * without being able to say which ruleset each belonged to.</p>
     */
    private static Map<String, Species> readSpeciesTable(String source) {
        record Draft(String genusId, Map<String, String> scalars) {
        }
        Map<String, Draft> drafts = new LinkedHashMap<>();
        for (Leaf leaf : scan(source)) {
            List<String> path = leaf.path();
            if (path.size() != 3 || !isSymbol(path.get(1)) || !isSymbol(path.get(2))) {
                continue;
            }
            drafts.computeIfAbsent(
                    path.get(2),
                    id -> new Draft(path.get(1), new LinkedHashMap<>())
            ).scalars().put(leaf.key(), leaf.value());
        }
        Map<String, List<Ruleset>> rulesets = readRulesetTables(source);
        Map<String, Species> species = new TreeMap<>();
        drafts.forEach((id, draft) -> species.put(id, new Species(
                id,
                draft.genusId(),
                draft.scalars().get("name"),
                longValue(draft.scalars().get("value")),
                rulesets.getOrDefault(id, List.of())
        )));
        return species;
    }

    // -------------------------------------------------------------- rulesets

    /**
     * Every species' spawn rulesets, keyed by species identifier.
     *
     * <p>A {@code 'rulesets': [} opens a list of dictionaries that belongs to
     * the most recently opened symbol — which is the species, in every module,
     * including the six organisms whose genus and species are one identifier.
     * The list is accumulated as text until its bracket closes, so a value
     * written across several lines is no different from one written on
     * one.</p>
     */
    private static Map<String, List<Ruleset>> readRulesetTables(String source) {
        Map<String, List<Ruleset>> byspecies = new LinkedHashMap<>();
        List<String> lines = source.lines().toList();
        String owner = null;
        for (int index = 0; index < lines.size(); index++) {
            String line = stripComment(lines.get(index)).strip();
            String[] assignment = keyAndValue(line);
            if (assignment == null) {
                continue;
            }
            if (isSymbol(assignment[0]) && assignment[1].startsWith("{")) {
                owner = assignment[0];
                continue;
            }
            if (!"rulesets".equals(assignment[0]) || !"[".equals(assignment[1])) {
                continue;
            }
            StringBuilder block = new StringBuilder();
            int depth = 1;
            int cursor = index + 1;
            while (cursor < lines.size() && depth > 0) {
                String raw = stripComment(lines.get(cursor++));
                depth += bracketDelta(raw);
                if (depth > 0) {
                    block.append(raw).append('\n');
                }
            }
            index = cursor - 1;
            if (owner != null) {
                byspecies.put(owner, parseRulesets(block.toString()));
            }
        }
        return byspecies;
    }

    /** How much a line changes the bracket depth, ignoring quoted text. */
    private static int bracketDelta(String line) {
        int delta = 0;
        char quote = 0;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '[' || character == '{' || character == '(') {
                delta++;
            } else if (character == ']' || character == '}' || character == ')') {
                delta--;
            }
        }
        return delta;
    }

    /** The dictionaries inside a {@code rulesets} list. */
    private static List<Ruleset> parseRulesets(String block) {
        List<Ruleset> rulesets = new ArrayList<>();
        for (String part : topLevelParts(block)) {
            String entry = part.strip();
            if (!entry.startsWith("{") || !entry.endsWith("}")) {
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (String pair : topLevelParts(entry.substring(1, entry.length() - 1))) {
                String[] assignment = keyAndValue(pair.strip());
                if (assignment != null) {
                    fields.put(assignment[0], assignment[1]);
                }
            }
            String volcanism = fields.get("volcanism");
            String word = scalar(volcanism == null ? "" : volcanism);
            rulesets.add(new Ruleset(
                    stringList(fields.get("body_type")),
                    stringList(fields.get("atmosphere")),
                    doubleValue(fields.get("min_gravity")),
                    doubleValue(fields.get("max_gravity")),
                    doubleValue(fields.get("min_temperature")),
                    doubleValue(fields.get("max_temperature")),
                    doubleValue(fields.get("min_pressure")),
                    doubleValue(fields.get("max_pressure")),
                    word,
                    stringList(volcanism)
            ));
        }
        return List.copyOf(rulesets);
    }

    /** The elements of an inline Python list, or nothing. */
    private static List<String> stringList(String raw) {
        if (raw == null) {
            return List.of();
        }
        String value = raw.strip();
        if (!value.startsWith("[") || !value.endsWith("]")) {
            return List.of();
        }
        List<String> elements = new ArrayList<>();
        for (String part : topLevelParts(value.substring(1, value.length() - 1))) {
            String element = scalar(part);
            if (element != null && !element.isBlank()) {
                elements.add(element);
            }
        }
        return List.copyOf(elements);
    }

    private static Double doubleValue(String raw) {
        String value = raw == null ? null : scalar(raw);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    // ---------------------------------------------------------------- scanner

    /** One scalar assignment and the chain of dictionary keys containing it. */
    private record Leaf(List<String> path, String key, String value) {
    }

    /**
     * Every scalar assignment in a Python dictionary literal, with its path.
     *
     * <p>Containers are pushed on {@code {} and {@code [} and popped on the
     * matching close, so a list of rulesets keeps the stack balanced without
     * being understood. A container opened without a quoted key — the module's
     * own assignment, or a dictionary inside a list — is pushed under the empty
     * name, which keeps depths honest and is why the readers above match on path
     * shape rather than on depth alone.</p>
     *
     * <p>Two shapes cost a first attempt at this and are handled explicitly. An
     * entry short enough to fit on one line is written on one line —
     * {@code '$Codex_Ent_Cone_Name;': {'name': 'Bark Mound', 'distance': 100,
     * 'multiple': False},} — and seven of the twenty-two genera are written that
     * way, so a scanner that only opens containers at end of line loses them
     * silently. And a key may be double-quoted: one genus in the upstream table
     * is, and no rule says which quote a Python author reaches for.</p>
     */
    private static List<Leaf> scan(String source) {
        List<Leaf> leaves = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        for (String rawLine : source.lines().toList()) {
            String line = stripComment(rawLine).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("}") || line.startsWith("]") || line.startsWith(")")) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
                continue;
            }
            String[] assignment = keyAndValue(line);
            if (assignment == null) {
                if (line.endsWith("{") || line.endsWith("[")) {
                    stack.add("");
                }
                continue;
            }
            String key = assignment[0];
            String value = assignment[1];
            if ("{".equals(value) || "[".equals(value)) {
                stack.add(key);
                continue;
            }
            if (value.startsWith("{") && value.endsWith("}")) {
                stack.add(key);
                for (String pair : topLevelParts(value.substring(1, value.length() - 1))) {
                    String[] inner = keyAndValue(pair.strip());
                    String scalar = inner == null ? null : scalar(inner[1]);
                    if (scalar != null) {
                        leaves.add(new Leaf(List.copyOf(stack), inner[0], scalar));
                    }
                }
                stack.removeLast();
                continue;
            }
            String scalar = scalar(value);
            if (scalar != null) {
                leaves.add(new Leaf(List.copyOf(stack), key, scalar));
            }
        }
        return leaves;
    }

    /**
     * A quoted key and everything assigned to it, or null.
     *
     * <p>The trailing comma of a dictionary entry belongs to the dictionary, not
     * to the value, and is removed here so a caller never has to know whether it
     * is looking at the last entry.</p>
     */
    private static String[] keyAndValue(String line) {
        if (line.isEmpty()) {
            return null;
        }
        char quote = line.charAt(0);
        if (quote != '\'' && quote != '"') {
            return null;
        }
        int closing = line.indexOf(quote, 1);
        if (closing < 0) {
            return null;
        }
        String rest = line.substring(closing + 1).stripLeading();
        if (!rest.startsWith(":")) {
            return null;
        }
        String value = rest.substring(1).strip();
        if (value.endsWith(",")) {
            value = value.substring(0, value.length() - 1).strip();
        }
        return new String[]{line.substring(1, closing), value};
    }

    /** The comma-separated parts of one line, ignoring commas inside anything. */
    private static List<String> topLevelParts(String content) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '{' || character == '[' || character == '(') {
                depth++;
            } else if (character == '}' || character == ']' || character == ')') {
                depth--;
            } else if (character == ',' && depth == 0) {
                parts.add(content.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(content.substring(start));
        return parts;
    }

    /** A scalar value, or null when the assignment opens a structure. */
    private static String scalar(String raw) {
        String value = raw.strip();
        if (value.length() >= 2
                && (value.startsWith("'") && value.endsWith("'")
                        || value.startsWith("\"") && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1);
        }
        return value.isEmpty()
                || value.contains("{")
                || value.contains("[")
                || value.contains("(")
                ? null
                : value;
    }

    /** Everything after a {@code #} that is not inside a string. */
    private static String stripComment(String line) {
        char quote = 0;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '#') {
                return line.substring(0, index);
            }
        }
        return line;
    }

    // ----------------------------------------------------------- identifiers

    static boolean isSymbol(String value) {
        return value != null
                && value.startsWith(SYMBOL_PREFIX)
                && value.endsWith(SYMBOL_SUFFIX);
    }

    /**
     * The variant identifier a colour key produces for one species.
     *
     * <p>{@code $Codex_Ent_Bacterial_01_Name;} plus {@code F} is
     * {@code $Codex_Ent_Bacterial_01_F_Name;}. A star class is written as the
     * table has it; a raw material is capitalised, because the table says
     * {@code yttrium} and the journal says {@code Yttrium}. Both readings are
     * checked against every variant these journals have actually seen.</p>
     */
    static String variantIdentifier(String speciesId, String colourKey) {
        String stem = speciesId.substring(
                0,
                speciesId.length() - SYMBOL_SUFFIX.length()
        );
        return stem + "_" + capitalisedMaterial(colourKey) + SYMBOL_SUFFIX;
    }

    private static String capitalisedMaterial(String colourKey) {
        if (colourKey.isEmpty() || !Character.isLowerCase(colourKey.charAt(0))) {
            return colourKey;
        }
        return Character.toUpperCase(colourKey.charAt(0))
                + colourKey.substring(1).toLowerCase(Locale.ROOT);
    }

    private static Integer integer(String value) {
        try {
            return value == null ? null : Integer.valueOf(value.strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Long longValue(String value) {
        try {
            return value == null ? null : Long.valueOf(value.strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Boolean bool(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.strip()) {
            case "True" -> Boolean.TRUE;
            case "False" -> Boolean.FALSE;
            default -> null;
        };
    }
}
