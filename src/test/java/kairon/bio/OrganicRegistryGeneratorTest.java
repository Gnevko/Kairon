package kairon.bio;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kairon.bio.JournalOrganicLabels.Harvest;
import kairon.bio.JournalOrganicLabels.Sale;
import kairon.bio.UpstreamOrganicTables.Genus;
import kairon.bio.UpstreamOrganicTables.Ruleset;
import kairon.bio.UpstreamOrganicTables.Species;
import kairon.bio.UpstreamOrganicTables.Tables;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes {@code config/organic-registry.json} — opt-in, run by hand.
 *
 * <p>The registry is generated rather than maintained, because a hand edit
 * survives exactly until the next regeneration. Three inputs, none of which is
 * copied into this repository:</p>
 *
 * <ul>
 *   <li>the pinned upstream tables, for what the game calls a genus and a
 *       species, what a sample is worth, and which colour a variant is;</li>
 *   <li>the Commander's own journals, for what the game calls all of that in
 *       the language the game is set to;</li>
 *   <li>this class, for the one rule that turns the second into coverage the
 *       journals never saw — and for refusing to apply it where it does not
 *       hold.</li>
 * </ul>
 *
 * <p>Run with:</p>
 * <pre>
 * mvnw.cmd test "-Dtest=OrganicRegistryGeneratorTest" ^
 *   "-Dkairon.bio.registryOutput=config/organic-registry.json" ^
 *   "-Dkairon.bio.journals=&lt;journal-directory&gt;" ^
 *   "-Dkairon.bio.journalLanguage=ru"
 * </pre>
 *
 * <p>{@code -Dkairon.bio.upstream=&lt;directory&gt;} reads local copies of the
 * pinned sources instead of downloading them. Without an output path the test
 * is skipped, so nothing here runs in an ordinary build.</p>
 *
 * <p>The assertions are the point as much as the file is. A journal identifier
 * the registry cannot name means the pinned revision is behind the game; a sale
 * whose value is not a multiple of the generated one means the numbers have been
 * revalued. Both are findings, and both fail here rather than reaching a
 * document.</p>
 */
final class OrganicRegistryGeneratorTest {

    private static final String OUTPUT_PROPERTY = "kairon.bio.registryOutput";
    private static final String JOURNALS_PROPERTY = "kairon.bio.journals";
    private static final String LANGUAGE_PROPERTY = "kairon.bio.journalLanguage";
    private static final String UPSTREAM_PROPERTY = "kairon.bio.upstream";

    private static final String SCHEMA = "kairon-organic-registry-v1";
    private static final String CANONICAL_LANGUAGE = "en";

    /** How the game joins a species to the colour of one of its variants. */
    private static final String COLOUR_SEPARATOR = " - ";

    /** The first-discovery payout, as a rule rather than as a stored field. */
    private static final long BONUS_MULTIPLE = 4L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void writesTheOrganicRegistryFromPinnedTablesAndOwnJournals() throws Exception {
        String configuredOutput = System.getProperty(OUTPUT_PROPERTY);
        Assumptions.assumeTrue(
                configuredOutput != null && !configuredOutput.isBlank(),
                () -> "set -D" + OUTPUT_PROPERTY
                        + "=<path> to regenerate the organic registry"
        );
        Path output = Path.of(configuredOutput).toAbsolutePath().normalize();

        String configuredJournals = System.getProperty(JOURNALS_PROPERTY);
        String language = System.getProperty(LANGUAGE_PROPERTY);
        if (configuredJournals != null && !configuredJournals.isBlank()) {
            assertTrue(
                    language != null && !language.isBlank(),
                    "-D" + LANGUAGE_PROPERTY + "=<code> must say which language "
                            + "the journals are written in"
            );
        }
        String configuredUpstream = System.getProperty(UPSTREAM_PROPERTY);
        Path upstream = configuredUpstream == null || configuredUpstream.isBlank()
                ? null
                : Path.of(configuredUpstream).toAbsolutePath().normalize();

        Tables tables = UpstreamOrganicTables.read(upstream);
        assertTrue(tables.genera().size() >= 20, "upstream genus table looks unread");
        assertTrue(tables.species().size() >= 100, "upstream species table looks unread");

        Harvest harvest = configuredJournals == null || configuredJournals.isBlank()
                ? null
                : JournalOrganicLabels.harvest(
                        Path.of(configuredJournals).toAbsolutePath().normalize()
                );

        Set<String> genusIdentifiers = allGenusIdentifiers(tables);
        Variants variants = variants(tables);
        Localisation localised = harvest == null
                ? Localisation.none()
                : localise(tables, genusIdentifiers, variants, harvest);

        ObjectNode document = document(
                tables, genusIdentifiers, variants, localised, harvest, language
        );
        Files.createDirectories(output.getParent());
        Files.writeString(output, serialise(document), StandardCharsets.UTF_8);

        String report = report(
                output, tables, variants, localised, harvest, language
        );
        System.out.println(report);
        Path reportFile = Path.of("var", "organic-registry-report.txt")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, report, StandardCharsets.UTF_8);

        assertTrue(Files.isRegularFile(output));
        if (harvest != null) {
            assertEquals(
                    List.of(),
                    unnameable(harvest, tables, variants),
                    "the pinned upstream cannot name an organism these journals "
                            + "saw; update the pins"
            );
            assertEquals(
                    List.of(),
                    misvalued(harvest, tables),
                    "a recorded sale disagrees with the generated value; the "
                            + "game has revalued exobiology"
            );
            // These journals were not all written in one language: nine codex
            // entries are read both in German and in Russian, because the game
            // was set to German for a while. That is harmless for star types
            // and planet classes, which this registry does not cover, and fatal
            // for an organism, which would be filed under the wrong language.
            assertEquals(
                    List.of(),
                    harvest.conflicts().keySet().stream()
                            .filter(harvest.taxa()::contains)
                            .toList(),
                    "an organism was read under two names; these journals are "
                            + "not all in the language they were declared to be"
            );
        }
    }

    // ------------------------------------------------------------- the shapes

    /** One variant: its identifier, its species, and the colour it is. */
    private record Variant(String id, String speciesId, String colour) {
    }

    /** Every variant the colour tables produce, keyed by identifier. */
    private record Variants(Map<String, Variant> byIdentifier) {
    }

    /**
     * The localised half, and how each string was arrived at.
     *
     * <p>{@code composed} names the identifiers whose string this class built
     * rather than read. Keeping the two apart is what makes the file honest: a
     * composed name is a claim about a rule, and the rule is only applied where
     * every observation of that genus agreed with it.</p>
     */
    private record Localisation(
            Map<String, String> names,
            Set<String> composed,
            Map<String, String> genusWords,
            Map<String, String> colourWords,
            List<String> refusals
    ) {
        static Localisation none() {
            return new Localisation(
                    Map.of(), Set.of(), Map.of(), Map.of(), List.of()
            );
        }
    }

    // ------------------------------------------------------------- generation

    /**
     * Every variant the upstream colour tables can produce.
     *
     * <p>A genus either has one table for all its species or one table per
     * species; both are read the same way, and the identifier is built by the
     * rule the journals confirm — the species symbol with the colour key
     * spliced in before {@code _Name;}.</p>
     */
    private static Variants variants(Tables tables) {
        Map<String, Variant> byIdentifier = new TreeMap<>();
        for (Species species : tables.species().values()) {
            Genus genus = tables.genera().get(species.genusId());
            if (genus == null) {
                continue;
            }
            genus.coloursFor(species.id()).forEach((key, colour) -> {
                String identifier =
                        UpstreamOrganicTables.variantIdentifier(species.id(), key);
                byIdentifier.put(
                        identifier,
                        new Variant(identifier, species.id(), colour)
                );
            });
        }
        return new Variants(byIdentifier);
    }

    /**
     * The localised names, read where the journals have them and composed where
     * one genus's own readings say how.
     *
     * <p>Three passes. The first learns each genus's word from the species
     * labels that were read: {@code "Бактерия Aurasus"} minus the epithet
     * {@code Aurasus} is {@code "Бактерия"}. Every reading of that genus has to
     * produce the same word or the genus is refused outright — which is what
     * happens to Radicoida, whose epithet is itself translated. The second pass
     * learns each colour's word the same way, from variant labels that start
     * with their own species label. The third writes the names.</p>
     */
    private static Localisation localise(
            Tables tables,
            Set<String> genusIdentifiers,
            Variants variants,
            Harvest harvest
    ) {
        Map<String, String> read = harvest.labels();
        List<String> refusals = new ArrayList<>();

        Map<String, String> genusWords = new TreeMap<>();
        for (Genus genus : tables.genera().values()) {
            Set<String> candidates = new LinkedHashSet<>();
            boolean refused = false;
            for (Species species : speciesOf(tables, genus.id())) {
                String label = read.get(species.id());
                String epithet = epithet(genus, species);
                if (label == null || epithet == null) {
                    continue;
                }
                if (!label.endsWith(" " + epithet)) {
                    refused = true;
                    refusals.add(genus.id() + ": read \"" + label
                            + "\" does not end in the epithet \"" + epithet + "\"");
                    continue;
                }
                candidates.add(label.substring(0, label.length() - epithet.length() - 1));
            }
            if (refused || candidates.size() != 1) {
                if (candidates.size() > 1) {
                    refusals.add(genus.id() + ": readings disagree on the genus "
                            + "word " + candidates);
                }
                continue;
            }
            genusWords.put(genus.id(), candidates.iterator().next());
        }

        Map<String, String> speciesNames = new TreeMap<>();
        Set<String> composed = new TreeSet<>();
        for (Species species : tables.species().values()) {
            String label = read.get(species.id());
            if (label != null) {
                speciesNames.put(species.id(), label);
                continue;
            }
            String word = genusWords.get(species.genusId());
            String epithet = epithet(tables.genera().get(species.genusId()), species);
            if (word != null && epithet != null) {
                speciesNames.put(species.id(), word + " " + epithet);
                composed.add(species.id());
            }
        }

        Map<String, Set<String>> colourCandidates = new TreeMap<>();
        Set<String> refusedSpecies = new TreeSet<>();
        for (Variant variant : variants.byIdentifier().values()) {
            String variantLabel = read.get(variant.id());
            String speciesLabel = read.get(variant.speciesId());
            if (variantLabel == null || speciesLabel == null) {
                continue;
            }
            String prefix = speciesLabel + COLOUR_SEPARATOR;
            if (!variantLabel.startsWith(prefix)) {
                // The game itself disagrees about this species: Frutexa
                // Metallicum is read "Кустарник Metallicum" as a species and
                // "Кустарник Металлический - изумруд" as a variant, the epithet
                // translated in one record and not in the other. Nothing can be
                // composed for it, because there is no one spelling to compose
                // from — so this species ships only what was actually read.
                refusals.add(variant.id() + ": read \"" + variantLabel
                        + "\" is not its species label plus a colour");
                refusedSpecies.add(variant.speciesId());
                continue;
            }
            colourCandidates
                    .computeIfAbsent(variant.colour(), colour -> new LinkedHashSet<>())
                    .add(variantLabel.substring(prefix.length()));
        }
        Map<String, String> colourWords = new TreeMap<>();
        colourCandidates.forEach((colour, candidates) -> {
            if (candidates.size() == 1) {
                colourWords.put(colour, candidates.iterator().next());
            } else {
                refusals.add(colour + ": readings disagree on the colour word "
                        + candidates);
            }
        });

        Map<String, String> names = new TreeMap<>(speciesNames);
        for (String genusIdentifier : genusIdentifiers) {
            String label = read.get(genusIdentifier);
            if (label != null) {
                names.put(genusIdentifier, label);
            }
        }
        for (Variant variant : variants.byIdentifier().values()) {
            String label = read.get(variant.id());
            if (label != null) {
                names.put(variant.id(), label);
                continue;
            }
            String speciesName = speciesNames.get(variant.speciesId());
            String colourWord = colourWords.get(variant.colour());
            if (speciesName != null
                    && colourWord != null
                    && !refusedSpecies.contains(variant.speciesId())) {
                names.put(variant.id(), speciesName + COLOUR_SEPARATOR + colourWord);
                composed.add(variant.id());
            }
        }
        return new Localisation(names, composed, genusWords, colourWords, refusals);
    }

    /**
     * The part of a species name that is not its genus, or null.
     *
     * <p>Null for the organisms whose name is not built that way at all — a bark
     * mound is not a {@code Cone Something} — and those get read names only.</p>
     */
    private static String epithet(Genus genus, Species species) {
        if (genus == null || genus.name() == null || species.name() == null) {
            return null;
        }
        String prefix = genus.name() + " ";
        return species.name().startsWith(prefix)
                ? species.name().substring(prefix.length())
                : null;
    }

    private static List<Species> speciesOf(Tables tables, String genusId) {
        return tables.species().values().stream()
                .filter(species -> genusId.equals(species.genusId()))
                .toList();
    }

    // --------------------------------------------------------- the document

    private static ObjectNode document(
            Tables tables,
            Set<String> genusIdentifiers,
            Variants variants,
            Localisation localised,
            Harvest harvest,
            String language
    ) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema", SCHEMA);

        ObjectNode meta = root.putObject("meta");
        meta.put("generatedOn", LocalDate.now().toString());
        ArrayNode languages = meta.putArray("languages");
        languages.add(CANONICAL_LANGUAGE);
        if (harvest != null) {
            languages.add(language);
        }
        ArrayNode sources = meta.putArray("sources");
        source(sources, "Silarn/EDMC-BioScan",
                UpstreamOrganicTables.BIO_SCAN_REVISION,
                "species names and sample values");
        source(sources, "Silarn/EDMC-ExploData",
                UpstreamOrganicTables.EXPLO_DATA_REVISION,
                "genus names, colony distances and variant colours");
        if (harvest != null) {
            ObjectNode own = sources.addObject();
            own.put("source", "commander journals");
            own.put("provides", language + " names");
            own.put("journals", harvest.journalCount());
            own.put("records", harvest.recordCount());
        }

        Map<String, Species> ownGenus = ownGenus(tables);

        ArrayNode genera = root.putArray("genera");
        for (String id : genusIdentifiers) {
            Genus genus = tables.genera().get(id);
            Species itself = ownGenus.get(id);
            ObjectNode entry = genera.addObject();
            entry.put("id", id);
            names(
                    entry,
                    genus != null && genus.name() != null
                            ? genus.name()
                            : (itself == null ? null : itself.name()),
                    localised.names().get(id),
                    language
            );
            if (genus != null && genus.colonyDistanceM() != null) {
                entry.put("colonyDistanceM", genus.colonyDistanceM());
            }
            if (genus != null && genus.multipleSpecies() != null) {
                entry.put("multipleSpecies", genus.multipleSpecies());
            }
            if (itself != null && itself.valueCr() != null) {
                entry.put("valueCr", itself.valueCr());
            }
            rulesets(entry, itself);
        }

        ArrayNode species = root.putArray("species");
        for (Species entry : tables.species().values()) {
            if (ownGenus.containsKey(entry.id())) {
                continue;
            }
            ObjectNode node = species.addObject();
            node.put("id", entry.id());
            node.put("genus", entry.genusId());
            names(node, entry.name(), localised.names().get(entry.id()), language);
            if (entry.valueCr() != null) {
                node.put("valueCr", entry.valueCr());
            }
            rulesets(node, entry);
        }

        ArrayNode variantNodes = root.putArray("variants");
        for (Variant variant : variants.byIdentifier().values()) {
            Species parent = tables.species().get(variant.speciesId());
            String canonical = parent == null || parent.name() == null
                    ? null
                    : parent.name() + COLOUR_SEPARATOR + variant.colour();
            String localName = localised.names().get(variant.id());
            if (canonical == null && localName == null) {
                continue;
            }
            ObjectNode node = variantNodes.addObject();
            node.put("id", variant.id());
            node.put("species", variant.speciesId());
            names(node, canonical, localName, language);
        }
        return root;
    }

    /**
     * The conditions under which one species grows, where they narrow anything.
     *
     * <p>Written under the six names the runtime reads, which are the six the
     * upstream constrains that this project consults. A species with no
     * rulesets at all, and a species one of whose rulesets constrains nothing
     * readable here, both get no {@code rulesets} property: an empty list and
     * an unconstrained alternative mean the same thing — every body admits it —
     * and saying that in two shapes would invite a reader to tell them
     * apart.</p>
     */
    private static void rulesets(ObjectNode node, Species entry) {
        if (entry == null || entry.rulesets().isEmpty()) {
            return;
        }
        if (entry.rulesets().stream().anyMatch(rule -> !rule.constrainsSomething())) {
            return;
        }
        ArrayNode listed = node.putArray("rulesets");
        for (Ruleset rule : entry.rulesets()) {
            ObjectNode written = listed.addObject();
            strings(written, "bodyTypes", rule.bodyTypes());
            strings(written, "atmospheres", rule.atmospheres());
            number(written, "minGravity", rule.minGravity());
            number(written, "maxGravity", rule.maxGravity());
            number(written, "minTemperature", rule.minTemperature());
            number(written, "maxTemperature", rule.maxTemperature());
            number(written, "minPressure", rule.minPressure());
            number(written, "maxPressure", rule.maxPressure());
            if (rule.volcanismWord() != null) {
                written.put("volcanism", rule.volcanismWord());
            } else if (!rule.volcanismFragments().isEmpty()) {
                strings(written, "volcanism", rule.volcanismFragments());
            }
        }
    }

    private static void strings(ObjectNode node, String property, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        ArrayNode listed = node.putArray(property);
        values.forEach(listed::add);
    }

    private static void number(ObjectNode node, String property, Double value) {
        if (value != null) {
            node.put(property, value);
        }
    }

    private static void source(
            ArrayNode sources,
            String repository,
            String revision,
            String provides
    ) {
        ObjectNode node = sources.addObject();
        node.put("repository", repository);
        node.put("revision", revision);
        node.put("license", "GPL-2.0");
        node.put("provides", provides);
    }

    /**
     * The name block, which is omitted rather than emptied.
     *
     * <p>Absence is how the rest of this project's contracts say nothing is
     * known, and a registry entry with no name in a language is exactly that:
     * the reader falls back to whatever the journal said.</p>
     */
    private static void names(
            ObjectNode entry,
            String canonical,
            String localised,
            String language
    ) {
        if (canonical == null && localised == null) {
            return;
        }
        ObjectNode names = entry.putObject("names");
        if (canonical != null) {
            names.put(CANONICAL_LANGUAGE, canonical);
        }
        if (localised != null && language != null
                && !CANONICAL_LANGUAGE.equals(language)) {
            names.put(language, localised);
        }
    }

    /**
     * The organisms whose genus and species are the same symbol.
     *
     * <p>Six of them: bark mounds, amphora plants, anemones, crystalline
     * shards, sinuous tubers and one stratum the upstream lists at the top
     * level. The game gives each one symbol, which is the game saying they are
     * one organism, so the registry gives them one entry — declared as a genus,
     * carrying the species' name where the genus has none and the species'
     * value either way. A variant that names one of them as its species names
     * something that is declared, which is all the file promises.</p>
     *
     * <p>Emitting both was the first thing the strict loader caught: two
     * entries for one identifier, and a lookup that would have silently taken
     * whichever came first.</p>
     */
    private static Map<String, Species> ownGenus(Tables tables) {
        Map<String, Species> itself = new TreeMap<>();
        tables.species().values().stream()
                .filter(species -> species.id().equals(species.genusId()))
                .forEach(species -> itself.put(species.id(), species));
        return itself;
    }

    private static Set<String> allGenusIdentifiers(Tables tables) {
        Set<String> identifiers = new TreeSet<>(tables.genera().keySet());
        tables.species().values().forEach(species ->
                identifiers.add(species.genusId()));
        return identifiers;
    }

    // ------------------------------------------------------------- the checks

    /** Journal taxa the generated registry cannot name. */
    private static List<String> unnameable(
            Harvest harvest,
            Tables tables,
            Variants variants
    ) {
        List<String> missing = new ArrayList<>();
        for (String identifier : harvest.taxa()) {
            if (!tables.genera().containsKey(identifier)
                    && !tables.species().containsKey(identifier)
                    && !variants.byIdentifier().containsKey(identifier)) {
                missing.add(identifier);
            }
        }
        return missing;
    }

    /**
     * Sales the generated values cannot account for.
     *
     * <p>A sale states the sum over a batch, so the check is divisibility rather
     * than equality, and the bonus is either absent or the four-fold
     * first-discovery payout. Both are the game's arithmetic, not this
     * project's.</p>
     */
    private static List<String> misvalued(Harvest harvest, Tables tables) {
        List<String> wrong = new ArrayList<>();
        for (Sale sale : harvest.sales()) {
            Species species = tables.species().get(sale.speciesId());
            if (species == null || species.valueCr() == null) {
                continue;
            }
            if (sale.value() % species.valueCr() != 0) {
                wrong.add(sale.speciesId() + ": sold for " + sale.value()
                        + ", which is not a multiple of " + species.valueCr());
            }
            if (sale.bonus() != 0 && sale.bonus() != BONUS_MULTIPLE * sale.value()) {
                wrong.add(sale.speciesId() + ": bonus " + sale.bonus()
                        + " is neither nothing nor " + BONUS_MULTIPLE
                        + " times " + sale.value());
            }
        }
        return wrong;
    }

    // ------------------------------------------------------------- the report

    private static String report(
            Path output,
            Tables tables,
            Variants variants,
            Localisation localised,
            Harvest harvest,
            String language
    ) {
        long namedGenera = tables.genera().values().stream()
                .filter(genus -> genus.name() != null)
                .count();
        long valued = tables.species().values().stream()
                .filter(species -> species.valueCr() != null)
                .count();
        long localisedSpecies = tables.species().keySet().stream()
                .filter(localised.names()::containsKey)
                .count();
        long localisedVariants = variants.byIdentifier().keySet().stream()
                .filter(localised.names()::containsKey)
                .count();

        StringBuilder report = new StringBuilder()
                .append(SCHEMA).append('\n')
                .append("output=").append(output).append('\n')
                .append("bioScan=").append(UpstreamOrganicTables.BIO_SCAN_REVISION)
                .append('\n')
                .append("exploData=").append(UpstreamOrganicTables.EXPLO_DATA_REVISION)
                .append('\n')
                .append("genera=").append(tables.genera().size())
                .append(" named=").append(namedGenera).append('\n')
                .append("species=").append(tables.species().size())
                .append(" valued=").append(valued).append('\n')
                .append("variants=").append(variants.byIdentifier().size())
                .append('\n');
        if (harvest == null) {
            return report.append("journals=none\n").toString();
        }
        report.append("journals=").append(harvest.journalCount())
                .append(" records=").append(harvest.recordCount())
                .append(" unreadableLines=").append(harvest.unreadableLineCount())
                .append('\n')
                .append("readTaxa=").append(harvest.taxa().size())
                .append(" readLabels=").append(harvest.labels().size())
                .append(" conflicts=").append(harvest.conflicts().size())
                .append('\n')
                .append(language).append(": genusWords=")
                .append(localised.genusWords().size())
                .append(" colourWords=").append(localised.colourWords().size())
                .append('\n')
                .append(language).append(": species=").append(localisedSpecies)
                .append(" variants=").append(localisedVariants)
                .append(" composed=").append(localised.composed().size())
                .append('\n')
                .append("sales=").append(harvest.sales().size()).append('\n');
        if (!localised.refusals().isEmpty()) {
            report.append("refused:\n");
            localised.refusals().forEach(refusal ->
                    report.append("  ").append(refusal).append('\n'));
        }
        if (!harvest.conflicts().isEmpty()) {
            report.append("conflicting readings:\n");
            harvest.conflicts().forEach((identifier, readings) ->
                    report.append("  ").append(identifier).append(' ')
                            .append(readings).append('\n'));
        }
        return report.toString();
    }

    // ---------------------------------------------------------- serialisation

    /**
     * The document as it is written: two-space indent, LF, final newline.
     *
     * <p>Deterministic on purpose. Everything is sorted by identifier, so a
     * regeneration that changes nothing produces no diff and one that changes
     * something produces only that.</p>
     */
    private static String serialise(ObjectNode document) throws IOException {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new RegistryPrinter();
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return MAPPER.writer(printer).writeValueAsString(document) + "\n";
    }

    /** A printer that writes {@code "key": value} rather than {@code "key" : value}. */
    private static final class RegistryPrinter extends DefaultPrettyPrinter {

        private RegistryPrinter() {
        }

        private RegistryPrinter(RegistryPrinter base) {
            super(base);
        }

        @Override
        public DefaultPrettyPrinter createInstance() {
            return new RegistryPrinter(this);
        }

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator generator)
                throws IOException {
            generator.writeRaw(": ");
        }
    }
}
