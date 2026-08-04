package kairon.state;

import kairon.semantics.BodyIdentity;
import java.util.Objects;

/**
 * The organic-sampling sequence currently in progress.
 *
 * <p>Present only while a sequence is active. {@code Analyse} ends the sequence
 * and clears this, so the record never describes a finished one; completion is
 * stated by the final trigger's structured fact instead.</p>
 *
 * <p>Body identity is {@code (systemAddress, bodyId)}. A body id alone is not an
 * identity: ids repeat across systems.</p>
 *
 * <p>Nothing here is inferred. There is no sample count, no required count, no
 * readiness flag and no next-stage prediction, because no journal event
 * establishes any of them.</p>
 */
record BiologicalSamplingProcess(
        long systemAddress,
        long bodyId,
        TaxonName genus,
        TaxonName species,
        TaxonName variant,
        BiologicalSamplingStage stage
) {

    BiologicalSamplingProcess {
        stage = Objects.requireNonNull(stage, "stage");
    }

    BodyIdentity body() {
        return new BodyIdentity(systemAddress, bodyId);
    }

    /**
     * Whether {@code other} scans the same organism on the same body.
     *
     * <p>Raw identifiers only. Two events differing solely in localised labels
     * are the same sequence; two events differing in identifier are not,
     * whatever their labels say.</p>
     */
    boolean sameSequenceAs(BiologicalSamplingProcess other) {
        return other != null
                && systemAddress == other.systemAddress
                && bodyId == other.bodyId
                && TaxonName.sameIdentity(genus, other.genus)
                && TaxonName.sameIdentity(species, other.species)
                && TaxonName.sameIdentity(variant, other.variant);
    }

    /**
     * This sequence advanced by {@code observed}, or {@code observed} itself
     * when it starts a different one.
     *
     * <p>Continuing keeps labels already established for the same identity. A
     * different sequence inherits nothing: carrying an old variant onto a new
     * organism would assert something no event stated.</p>
     */
    BiologicalSamplingProcess continuedAs(BiologicalSamplingProcess observed) {
        if (observed == null || !sameSequenceAs(observed)) {
            return observed;
        }
        return new BiologicalSamplingProcess(
                observed.systemAddress,
                observed.bodyId,
                TaxonName.retainingLabel(observed.genus, genus),
                TaxonName.retainingLabel(observed.species, species),
                TaxonName.retainingLabel(observed.variant, variant),
                observed.stage
        );
    }
}
