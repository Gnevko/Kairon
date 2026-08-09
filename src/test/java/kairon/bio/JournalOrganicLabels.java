package kairon.bio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * What the Commander's own journals say organisms are called.
 *
 * <p>The game writes every organic identifier beside a rendering of it in
 * whatever language the game is set to. That rendering is the only source of a
 * localised name this project has, and it is the Commander's own data rather
 * than anyone else's: no licence, no download, no third party. It is also
 * exactly one language, however many journals are read — a Russian client emits
 * Russian and nothing else.</p>
 *
 * <p>Four records carry the pairs. {@code ScanOrganic} and
 * {@code SellOrganicData} name all three taxon levels, {@code SAASignalsFound}
 * names genera, and {@code CodexEntry} names whatever was logged. The sale
 * record is read for a second reason: it is the only place the game states what
 * a sample was worth, and that is what makes the generated values checkable.</p>
 *
 * <p>A label is kept verbatim. The game is not internally consistent — it writes
 * {@code "Бактерия Bullaris - красный"} and {@code "Бактерия Informem -
 * Кобальт"}, one colour lower-case and one capitalised — and normalising that
 * away would replace what the game says with what this project would prefer it
 * said.</p>
 */
final class JournalOrganicLabels {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JournalOrganicLabels() {
    }

    /** One species sold, as the game priced it. */
    record Sale(String speciesId, long value, long bonus) {
        Sale {
            speciesId = Objects.requireNonNull(speciesId, "speciesId");
        }
    }

    /**
     * Everything read out of one journal directory.
     *
     * <p>{@code labels} keeps the most frequent rendering of each identifier.
     * {@code conflicts} keeps every identifier that was rendered more than one
     * way, so a disagreement is reported rather than silently resolved.</p>
     */
    record Harvest(
            Map<String, String> labels,
            Map<String, List<String>> conflicts,
            Set<String> taxa,
            List<Sale> sales,
            int journalCount,
            int recordCount,
            int unreadableLineCount
    ) {
        Harvest {
            labels = Collections.unmodifiableMap(new TreeMap<>(labels));
            conflicts = Collections.unmodifiableMap(new TreeMap<>(conflicts));
            taxa = Collections.unmodifiableSet(new TreeSet<>(taxa));
            sales = List.copyOf(sales);
        }
    }

    static Harvest harvest(Path directory) throws IOException {
        Map<String, Map<String, Integer>> renderings = new LinkedHashMap<>();
        Set<String> taxa = new TreeSet<>();
        List<Sale> sales = new ArrayList<>();
        int journals = 0;
        int records = 0;
        int unreadable = 0;

        for (Path journal : journalFiles(directory)) {
            journals++;
            try (Stream<String> lines = Files.lines(journal, StandardCharsets.UTF_8)) {
                for (String rawLine : (Iterable<String>) lines::iterator) {
                    String line = withoutByteOrderMark(rawLine).strip();
                    if (line.isEmpty()) {
                        continue;
                    }
                    JsonNode record;
                    try {
                        record = MAPPER.readTree(line);
                    } catch (IOException notJson) {
                        unreadable++;
                        continue;
                    }
                    if (readRecord(record, renderings, taxa, sales)) {
                        records++;
                    }
                }
            }
        }

        Map<String, String> labels = new TreeMap<>();
        Map<String, List<String>> conflicts = new TreeMap<>();
        renderings.forEach((identifier, counted) -> {
            labels.put(identifier, mostFrequent(counted));
            if (counted.size() > 1) {
                conflicts.put(identifier, List.copyOf(counted.keySet()));
            }
        });
        return new Harvest(
                labels, conflicts, taxa, sales, journals, records, unreadable
        );
    }

    // ---------------------------------------------------------------- records

    private static boolean readRecord(
            JsonNode record,
            Map<String, Map<String, Integer>> renderings,
            Set<String> taxa,
            List<Sale> sales
    ) {
        String event = text(record, "event");
        if (event == null) {
            return false;
        }
        switch (event) {
            case "ScanOrganic" -> {
                readTaxa(record, renderings, taxa);
                return true;
            }
            case "SellOrganicData" -> {
                for (JsonNode entry : array(record, "BioData")) {
                    readTaxa(entry, renderings, taxa);
                    String species = text(entry, "Species");
                    Long value = integral(entry, "Value");
                    if (species != null && value != null) {
                        Long bonus = integral(entry, "Bonus");
                        sales.add(new Sale(species, value, bonus == null ? 0L : bonus));
                    }
                }
                return true;
            }
            case "SAASignalsFound" -> {
                for (JsonNode entry : array(record, "Genuses")) {
                    String genus = text(entry, "Genus");
                    remember(taxa, genus);
                    record(renderings, genus, text(entry, "Genus_Localised"));
                }
                return true;
            }
            case "CodexEntry" -> {
                record(renderings, text(record, "Name"), text(record, "Name_Localised"));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static void readTaxa(
            JsonNode node,
            Map<String, Map<String, Integer>> renderings,
            Set<String> taxa
    ) {
        for (String level : List.of("Genus", "Species", "Variant")) {
            String identifier = text(node, level);
            remember(taxa, identifier);
            record(renderings, identifier, text(node, level + "_Localised"));
        }
    }

    /**
     * One organic identifier the game actually named.
     *
     * <p>Kept apart from the labels because a codex entry can be a geological
     * or a Guardian find, and the registry is not expected to know those. What
     * this set holds is what the registry must be able to name.</p>
     */
    private static void remember(Set<String> taxa, String identifier) {
        if (UpstreamOrganicTables.isSymbol(identifier)) {
            taxa.add(identifier);
        }
    }

    /**
     * One reading of one identifier.
     *
     * <p>An identifier with no rendering beside it is silence, not a name, and
     * is not recorded: the game omits {@code _Localised} where the symbol is
     * already the only spelling there is.</p>
     */
    private static void record(
            Map<String, Map<String, Integer>> renderings,
            String identifier,
            String label
    ) {
        if (!UpstreamOrganicTables.isSymbol(identifier)
                || label == null
                || label.isBlank()) {
            return;
        }
        renderings
                .computeIfAbsent(identifier, id -> new LinkedHashMap<>())
                .merge(label, 1, Integer::sum);
    }

    private static String mostFrequent(Map<String, Integer> counted) {
        return counted.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
    }

    // ----------------------------------------------------------------- typing

    private static List<Path> journalFiles(Path directory) throws IOException {
        List<Path> journals = new ArrayList<>();
        try (DirectoryStream<Path> entries =
                     Files.newDirectoryStream(directory, "Journal.*.log")) {
            entries.forEach(journals::add);
        }
        Collections.sort(journals);
        return journals;
    }

    private static String withoutByteOrderMark(String line) {
        return line.isEmpty() || line.charAt(0) != '﻿'
                ? line
                : line.substring(1);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static Long integral(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                ? value.longValue()
                : null;
    }

    private static Iterable<JsonNode> array(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isArray() ? value : List.of();
    }
}
