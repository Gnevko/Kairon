package kairon.semantics;

import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.ObservationDraft.SourcePosition;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The immutable model-independent semantic view of one observation.
 *
 * <p>Belongs to the same post-projection moment as the canonical state
 * snapshot and the behavior situation captured for the same
 * {@code busSequence}. It contains no JSON, no prompt wording, and no model
 * decision.</p>
 *
 * <p>{@code captureMode} and {@code effectRetention} travel with the effects
 * rather than being left behind at the publication, and the retention is read
 * here and now — the effect accumulator keeps a {@code RESTORE_ONLY}
 * observation's effects out of every later turn.</p>
 *
 * <p>Both are copied from the {@code AppliedObservation}; neither is classified
 * here, so the envelope cannot disagree with the value that owns the answer.</p>
 *
 * <p>Ordering: {@code structuredFacts}, {@code stateChanges} and
 * {@code unresolvedFacts} preserve the deterministic order in which the
 * projection produced them. Collections are never null; empty means the
 * observation produced nothing of that kind.</p>
 */
public record SemanticObservationEnvelope(
        long busSequence,
        ObservationSource source,
        SemanticSourceRole sourceRole,
        ObservationCaptureMode captureMode,
        EffectRetention effectRetention,
        SourcePosition sourcePosition,
        Optional<Instant> sourceTime,
        Instant observedAt,
        String rawObservationType,
        List<SemanticFact> structuredFacts,
        List<SemanticStateChange> stateChanges,
        List<UnresolvedFact> unresolvedFacts
) {

    public SemanticObservationEnvelope {
        if (busSequence < 1) {
            throw new IllegalArgumentException(
                    "busSequence must be positive"
            );
        }
        source = Objects.requireNonNull(source, "source");
        sourceRole = Objects.requireNonNull(sourceRole, "sourceRole");
        captureMode = Objects.requireNonNull(captureMode, "captureMode");
        effectRetention = Objects.requireNonNull(
                effectRetention,
                "effectRetention"
        );
        sourcePosition = Objects.requireNonNull(
                sourcePosition,
                "sourcePosition"
        );
        sourceTime = Objects.requireNonNull(sourceTime, "sourceTime");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        rawObservationType = requireNonBlank(
                rawObservationType,
                "rawObservationType"
        );
        structuredFacts = List.copyOf(Objects.requireNonNull(
                structuredFacts,
                "structuredFacts"
        ));
        stateChanges = List.copyOf(Objects.requireNonNull(
                stateChanges,
                "stateChanges"
        ));
        unresolvedFacts = List.copyOf(Objects.requireNonNull(
                unresolvedFacts,
                "unresolvedFacts"
        ));
        requireOwnedProvenance(
                busSequence,
                sourceRole,
                structuredFacts,
                stateChanges,
                unresolvedFacts
        );
    }

    /** Whether this observation produced any semantic effect at all. */
    public boolean empty() {
        return structuredFacts.isEmpty()
                && stateChanges.isEmpty()
                && unresolvedFacts.isEmpty();
    }

    /** Whether this observation changed canonical state. */
    public boolean changedState() {
        return !stateChanges.isEmpty();
    }

    private static void requireOwnedProvenance(
            long busSequence,
            SemanticSourceRole sourceRole,
            List<SemanticFact> structuredFacts,
            List<SemanticStateChange> stateChanges,
            List<UnresolvedFact> unresolvedFacts
    ) {
        for (SemanticFact fact : structuredFacts) {
            requireOwned(busSequence, sourceRole, fact.provenance());
        }
        for (SemanticStateChange change : stateChanges) {
            requireOwned(busSequence, sourceRole, change.provenance());
        }
        for (UnresolvedFact unresolved : unresolvedFacts) {
            requireOwned(busSequence, sourceRole, unresolved.provenance());
        }
    }

    private static void requireOwned(
            long busSequence,
            SemanticSourceRole sourceRole,
            SemanticProvenance provenance
    ) {
        if (provenance.busSequence() != busSequence) {
            throw new IllegalArgumentException(
                    "semantic provenance does not belong to this envelope"
            );
        }
        if (provenance.sourceRole() != sourceRole) {
            throw new IllegalArgumentException(
                    "semantic provenance role does not match this envelope"
            );
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
