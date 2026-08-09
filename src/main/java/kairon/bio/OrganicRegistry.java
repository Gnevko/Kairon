package kairon.bio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * What the game calls an organism, in whichever language it is being asked for.
 *
 * <p>One immutable lookup, keyed by the game's own {@code $Codex_Ent_…_Name;}
 * symbol at every taxon level. The symbol is the identity; a name is display
 * text and is never compared, never merged and never a key. That is the same
 * rule {@code TaxonName} states for a journal record, and it is the reason this
 * registry can exist at all: a name changes with the language, the symbol does
 * not.</p>
 *
 * <p>Kairon needs this because the journal only ever renders an organism in the
 * language the <em>game</em> is set to. Without a registry, which language
 * Kairon names organisms in is decided by a setting in another application —
 * set the client to English and a Russian-speaking Kairon starts saying English
 * names. With one, {@code observer.outputLanguage} decides.</p>
 *
 * <h2>What is here and what is not</h2>
 * <p>Names, what one sample of an organism is worth, which organisms belong to
 * which, and the conditions under which a species is known to grow. The value
 * was loaded and not exposed until something read it; it now has two readers —
 * the analysis turn, which tells the Commander what the sample just collected
 * pays, and the GUI tab that lists them. The rules and the parent links arrived
 * together and for one reader, {@link OrganicPredictor}, because narrowing a
 * genus needs both. {@code colonyDistanceM} is still only in the file, for the
 * same reason it was: nothing reads it, and a field carried for a reader that
 * does not exist is weight bought with nothing.</p>
 *
 * <p>This class holds what the file says and answers lookups. It decides
 * nothing about a body — that is {@link OrganicPredictor}, kept separate
 * because naming what was found and predicting what could be there are
 * different jobs with different failure modes.</p>
 */
public final class OrganicRegistry {

    /**
     * The language the registry's own names are written in.
     *
     * <p>A property of the file, not of any reader: the generator takes English
     * from the upstream tables and every other language from the Commander's
     * journals, so English is the one language every entry has.</p>
     */
    public static final String CANONICAL_LANGUAGE = "en";

    /** No registry at all: every lookup misses and every caller falls back. */
    public static final OrganicRegistry EMPTY =
            new OrganicRegistry(Map.of(), Map.of(), Map.of(), Map.of());

    private final Map<String, Map<String, String>> names;
    private final Map<String, Long> values;
    private final Map<String, List<String>> children;
    private final Map<String, List<OrganicRuleset>> rulesets;

    OrganicRegistry(
            Map<String, Map<String, String>> names,
            Map<String, Long> values,
            Map<String, String> parents,
            Map<String, List<OrganicRuleset>> rulesets
    ) {
        Map<String, Map<String, String>> copied = new TreeMap<>();
        Objects.requireNonNull(names, "names").forEach((identifier, byLanguage) ->
                copied.put(
                        identifier,
                        Collections.unmodifiableMap(new TreeMap<>(byLanguage))
                ));
        this.names = Collections.unmodifiableMap(copied);
        this.values = Collections.unmodifiableMap(new TreeMap<>(
                Objects.requireNonNull(values, "values")
        ));
        Map<String, List<String>> byParent = new TreeMap<>();
        Objects.requireNonNull(parents, "parents").forEach((child, parent) ->
                byParent.computeIfAbsent(parent, key -> new ArrayList<>()).add(child));
        byParent.replaceAll((parent, listed) -> {
            listed.sort(Comparator.naturalOrder());
            return List.copyOf(listed);
        });
        this.children = Collections.unmodifiableMap(byParent);
        Map<String, List<OrganicRuleset>> rules = new TreeMap<>();
        Objects.requireNonNull(rulesets, "rulesets").forEach((identifier, listed) ->
                rules.put(identifier, List.copyOf(listed)));
        this.rulesets = Collections.unmodifiableMap(rules);
    }

    /**
     * What one organism is called in one language.
     *
     * <p>Empty when the registry does not know the organism, or knows it and
     * has no name for it in that language. The two are the same answer on
     * purpose: the caller's fallback does not change between them, and telling
     * them apart would invite a second one.</p>
     */
    public Optional<String> name(String identifier, String language) {
        if (identifier == null || language == null) {
            return Optional.empty();
        }
        Map<String, String> byLanguage = names.get(identifier);
        return byLanguage == null
                ? Optional.empty()
                : Optional.ofNullable(byLanguage.get(language));
    }

    /** Whether the registry has heard of this organism in any language. */
    public boolean knows(String identifier) {
        return identifier != null && names.containsKey(identifier);
    }

    /**
     * What Vista Genomics pays for one sample of this organism, in credits.
     *
     * <p>The base price, before any bonus. Empty for a taxon that has no price
     * of its own: a genus has none unless it is also its own species, and a
     * colour variant has none at all — the game pays for the species.</p>
     */
    public OptionalLong valueCr(String identifier) {
        Long value = identifier == null ? null : values.get(identifier);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    /**
     * Every organism that has a price, named and sorted by that name.
     *
     * <p>For the one reader that wants the whole table rather than one lookup.
     * The name is the one asked for, else the canonical one, else the game's
     * own symbol — which is not a name and is shown anyway, because this is a
     * diagnostic view and a row with a price and no label would be a row
     * nobody can act on.</p>
     */
    public List<PricedOrganism> priced(String language) {
        List<PricedOrganism> priced = new ArrayList<>(values.size());
        values.forEach((identifier, value) -> priced.add(new PricedOrganism(
                identifier,
                name(identifier, language)
                        .or(() -> name(identifier, CANONICAL_LANGUAGE))
                        .orElse(identifier),
                value
        )));
        priced.sort(Comparator.comparing(PricedOrganism::name));
        return List.copyOf(priced);
    }

    /** One row of {@link #priced}. */
    public record PricedOrganism(String identifier, String name, long valueCr) {

        public PricedOrganism {
            identifier = Objects.requireNonNull(identifier, "identifier");
            name = Objects.requireNonNull(name, "name");
        }
    }

    /**
     * The organisms declared as belonging to this one, sorted by identifier.
     *
     * <p>Species for a genus, colour variants for a species, nothing for a
     * variant. Empty for an organism the registry does not know, which is the
     * same answer as for one it knows and that has no children — the caller's
     * next step does not change between them.</p>
     */
    public List<String> childrenOf(String identifier) {
        List<String> listed = identifier == null ? null : children.get(identifier);
        return listed == null ? List.of() : listed;
    }

    /**
     * The conditions under which one species is known to grow.
     *
     * <p>Empty for a species the file states no rules for, and that is not the
     * same as "grows nowhere": it is the file having nothing to say. A caller
     * narrowing a genus must treat it as admitting every body, because
     * excluding a species on missing evidence is how a floor stops being
     * one.</p>
     */
    public List<OrganicRuleset> rulesetsOf(String identifier) {
        List<OrganicRuleset> listed = identifier == null
                ? null
                : rulesets.get(identifier);
        return listed == null ? List.of() : listed;
    }

    /** How many organisms are recorded, across all three taxon levels. */
    public int size() {
        return names.size();
    }

    public boolean isEmpty() {
        return names.isEmpty();
    }
}
