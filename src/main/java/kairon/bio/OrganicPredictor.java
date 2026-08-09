package kairon.bio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * What a surveyed body is worth at least (ADR-0030).
 *
 * <p>The surface scan names genera and the game prices species, so the scan
 * alone is not a number: a genus averages nine or ten species and they differ
 * by up to a factor of twelve. This narrows each genus by the conditions of the
 * body and answers the cheapest total that is still possible.</p>
 *
 * <h2>Why a floor</h2>
 * <p>This is the only thing in the request Kairon infers rather than reads, so
 * it is shaped to fail safely. Excluding the species that is really there is
 * the one error that would make Kairon lie; including species that are not
 * there only lowers the answer. Every decision here therefore leans the same
 * way — an unknown condition admits, an unstated ruleset admits, an unpriced
 * species is not silently skipped — and the result is a bound rather than a
 * guess.</p>
 *
 * <p>Measured against this Commander's own journals, 82 samples out of 82 had
 * their true species among the survivors, and the cheapest survivor was the
 * true species in 61 of them.</p>
 */
public final class OrganicPredictor {

    private final OrganicRegistry registry;

    public OrganicPredictor(OrganicRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * The species of one genus that the body's conditions still allow.
     *
     * <p>Sorted by identifier, as the registry lists them. A species the file
     * states no rules for is admitted: the file having nothing to say is not
     * the species growing nowhere.</p>
     */
    public List<String> candidates(String genus, OrganicConditions conditions) {
        Objects.requireNonNull(conditions, "conditions");
        List<String> declared = registry.childrenOf(genus);
        if (declared.isEmpty() && registry.knows(genus)) {
            // Six organisms are their own species — Bark Mound and the rest —
            // and the file declares them once, as a genus. The genus is then
            // the only candidate it can have.
            declared = List.of(genus);
        }
        List<String> admitted = new ArrayList<>();
        for (String species : declared) {
            List<OrganicRuleset> rules = registry.rulesetsOf(species);
            if (rules.isEmpty()
                    || rules.stream().anyMatch(rule -> rule.admits(conditions))) {
                admitted.add(species);
            }
        }
        return List.copyOf(admitted);
    }

    /**
     * The cheapest one sample of this genus could be worth here, in credits.
     *
     * <p>Empty when nothing survives the conditions, and empty when something
     * survives that the file has no price for — an unpriced survivor could be
     * the cheapest, so a minimum taken over the rest would be a claim the file
     * cannot support.</p>
     */
    public OptionalLong floorValueCr(String genus, OrganicConditions conditions) {
        List<String> admitted = candidates(genus, conditions);
        if (admitted.isEmpty()) {
            return OptionalLong.empty();
        }
        long floor = Long.MAX_VALUE;
        for (String species : admitted) {
            OptionalLong value = registry.valueCr(species);
            if (value.isEmpty()) {
                return OptionalLong.empty();
            }
            floor = Math.min(floor, value.orElseThrow());
        }
        return OptionalLong.of(floor);
    }

    /**
     * The cheapest a full set of samples from these genera could be worth.
     *
     * <p>One sample of each genus, which is what a body pays out: the game
     * prices the species and the Commander collects each genus once. A genus
     * that survives nothing and a genus with an unpriced survivor both drop
     * out, and the sum over what is left is still a floor over what is left —
     * the answer understates, which is the direction it is allowed to be wrong
     * in. Empty when every genus dropped out, because a total of nothing is
     * not a total of zero.</p>
     */
    public OptionalLong floorValueCr(
            Collection<String> genera,
            OrganicConditions conditions
    ) {
        Objects.requireNonNull(genera, "genera");
        Set<String> distinct = new LinkedHashSet<>(genera);
        long total = 0L;
        boolean any = false;
        for (String genus : distinct) {
            OptionalLong floor = floorValueCr(genus, conditions);
            if (floor.isPresent()) {
                total += floor.orElseThrow();
                any = true;
            }
        }
        return any ? OptionalLong.of(total) : OptionalLong.empty();
    }
}
