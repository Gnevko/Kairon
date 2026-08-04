package kairon.state;

import java.util.Objects;

/**
 * One taxon as the journal names it: a raw identifier plus the game's optional
 * localised rendering.
 *
 * <p>{@code identifier} is the canonical identity and the only thing compared.
 * {@code label} is display text that may be absent, may change with the game's
 * language setting, and is never an identity key. A taxon with no raw
 * identifier has no identity at all, which is why {@link #of} returns
 * {@code null} rather than inventing one from the label.</p>
 */
record TaxonName(String identifier, String label) {

    TaxonName {
        identifier = Objects.requireNonNull(identifier, "identifier");
        if (identifier.isBlank()) {
            throw new IllegalArgumentException(
                    "taxon identifier must not be blank"
            );
        }
        if (label != null && label.isBlank()) {
            throw new IllegalArgumentException(
                    "taxon label must not be blank when present"
            );
        }
    }

    /**
     * @return {@code null} when the journal supplied no raw identifier; a
     *         localised label alone establishes no taxon
     */
    static TaxonName of(String identifier, String label) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        return new TaxonName(
                identifier,
                label == null || label.isBlank() ? null : label
        );
    }

    /** Identity comparison: the raw identifier alone, never the label. */
    static boolean sameIdentity(TaxonName first, TaxonName second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.identifier.equals(second.identifier);
    }

    /**
     * The observed taxon, keeping a label already known for the same identity.
     *
     * <p>A later event in one sequence may omit {@code _Localised}. That is an
     * absent field, not a retraction, so an established label survives it.</p>
     */
    static TaxonName retainingLabel(TaxonName observed, TaxonName known) {
        if (observed == null || observed.label != null) {
            return observed;
        }
        return sameIdentity(observed, known)
                ? new TaxonName(observed.identifier, known.label)
                : observed;
    }
}
