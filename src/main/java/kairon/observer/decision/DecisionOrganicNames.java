package kairon.observer.decision;

import kairon.bio.OrganicConditions;
import kairon.bio.OrganicPredictor;
import kairon.bio.OrganicRegistry;
import kairon.semantics.SemanticValue;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the document calls an organism.
 *
 * <p>One rule for every place an organism is named — the {@code organism} field
 * on a sampling event and on a sampling change, the {@code organisms} a surface
 * scan lists, and the {@code collected} and {@code remaining} of
 * {@code context.biology}. Before ADR-0028 there were two: a sampling event
 * carried the journal's own rendering, so a Russian client put
 * {@code "organism":"Бактерия Aurasus - лайм"} in an English document, while a
 * genus list carried the middle of the game's internal symbol, so the same turn
 * said {@code Bacterial} where the game says {@code Bacterium}.</p>
 *
 * <h2>Three rungs, in this order</h2>
 * <ol>
 *   <li><b>The registry, in the configured output language.</b> This is the
 *       point of having one: which language Kairon names organisms in stops
 *       being decided by a setting in another application.</li>
 *   <li><b>The word the game itself used.</b> Present whenever the journal
 *       rendered the name, which is almost always, and it is in the language
 *       the game is set to. This is what every name was before the registry
 *       existed, so a registry that has not heard of an organism costs
 *       nothing.</li>
 *   <li><b>The registry's canonical name.</b> A real name in a language that is
 *       not the one asked for beats no name at all — and the case it covers is
 *       an organism the game named in a third language, which these journals
 *       have (nine codex entries are read in both German and Russian).</li>
 * </ol>
 *
 * <p>Below that, nothing. The symbol is not a word: {@code Shrubs} is not what
 * the game calls Frutexa in any language, and sending it was the defect ADR-0028
 * names. Where no rung answers, the organism is not named — the same silence
 * this contract uses everywhere else for a fact Kairon does not have.</p>
 */
public final class DecisionOrganicNames {

    /** The language the registry's own names are written in. */
    public static final String CANONICAL_LANGUAGE =
            OrganicRegistry.CANONICAL_LANGUAGE;

    private final OrganicRegistry registry;
    private final String language;
    private final OrganicPredictor predictor;

    public DecisionOrganicNames(OrganicRegistry registry, String language) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.language = Objects.requireNonNull(language, "language");
        this.predictor = new OrganicPredictor(this.registry);
    }

    /**
     * Naming with no registry behind it: the journal's word, or nothing.
     *
     * <p>Exactly the {@code bio.registryFile: null} configuration, and what a
     * test that is not about naming should use.</p>
     */
    public static DecisionOrganicNames withoutRegistry() {
        return new DecisionOrganicNames(OrganicRegistry.EMPTY, CANONICAL_LANGUAGE);
    }

    /**
     * What to call the organism {@code identifier}, or null.
     *
     * @param identifier   the game's {@code $Codex_Ent_…_Name;} symbol
     * @param journalLabel the rendering the journal carried beside it, or null
     */
    public String name(String identifier, String journalLabel) {
        Optional<String> configured = registry.name(identifier, language);
        if (configured.isPresent()) {
            return configured.get();
        }
        if (journalLabel != null && !journalLabel.isBlank()) {
            return journalLabel;
        }
        return registry.name(identifier, CANONICAL_LANGUAGE).orElse(null);
    }

    /**
     * The same, as the document's own values.
     *
     * <p>Returns {@link SemanticValue#unknown()} where nothing names it, so a
     * caller adds the field without deciding again whether to.</p>
     */
    public SemanticValue name(SemanticValue identifier, SemanticValue journalLabel) {
        String named = name(plainText(identifier), plainText(journalLabel));
        return named == null ? SemanticValue.unknown() : SemanticValue.ofText(named);
    }

    /**
     * What one sample of this organism pays at Vista Genomics, or empty.
     *
     * <p>The base price the game publishes, before any bonus. Empty with no
     * registry, and empty for a taxon the registry prices nothing for — the
     * game pays for a species, so a colour variant has no price of its own.</p>
     */
    public OptionalLong sampleValueCr(SemanticValue speciesIdentifier) {
        return registry.valueCr(plainText(speciesIdentifier));
    }

    /**
     * The least a full set of samples from these genera could be worth here.
     *
     * <p>The surface scan names genera and the game prices species, so this
     * narrows each genus by the conditions of the body and sums the cheapest
     * survivors (ADR-0030). Empty when no genus survives, when a survivor has
     * no price, and — as with every other rung here — when there is no registry
     * at all.</p>
     */
    public OptionalLong floorValueCr(
            Collection<String> genera,
            OrganicConditions conditions
    ) {
        return genera == null || genera.isEmpty() || conditions == null
                ? OptionalLong.empty()
                : predictor.floorValueCr(genera, conditions);
    }

    /** A value's text, whether it was written as text or as a symbol. */
    private static String plainText(SemanticValue value) {
        return switch (value) {
            case null -> null;
            case SemanticValue.TextValue text -> text.value();
            case SemanticValue.SymbolicValue symbol -> symbol.symbol();
            default -> null;
        };
    }
}
