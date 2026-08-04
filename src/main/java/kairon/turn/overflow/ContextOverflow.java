package kairon.turn.overflow;

import java.util.List;
import java.util.Objects;

/**
 * A turn whose mandatory semantic context did not fit the character budget.
 *
 * <p>Fail closed. The provider was not called, no comment exists, no silence
 * was synthesised, and nothing was retried at a different budget or with less
 * content. This record is what the trace and the GUI get instead of a
 * decision.</p>
 *
 * <p>It carries enough to diagnose the turn without re-running it: which turn,
 * which triggers, how far over, and which mandatory sections dominate.</p>
 *
 * <h2>Why it lives in a package of its own</h2>
 * <p>The compactor produces the failure, the turn coordinator acts on it and
 * the trace writer records it. Holding it in the observer made
 * {@code kairon.trace} import the observer, while the observer already imports
 * the trace writer — a cycle around a value that has no behaviour beyond its
 * own invariants. It is projected from the compactor's typed failure rather
 * than constructed here from raw numbers, so the sizing cannot be restated
 * differently by whoever builds it.</p>
 */
public record ContextOverflow(
        long turnSequence,
        long firstTriggerBusSequence,
        long finalTriggerBusSequence,
        int mandatoryCharacterCount,
        int configuredCharacterBudget,
        int originalCharacterCount,
        int overshootCharacters,
        List<SectionWeight> largestMandatorySections
) {

    /** What the GUI shows. Never presented as commentary. */
    public static final String DIAGNOSTIC_MESSAGE =
            "LLM commentary skipped: semantic context exceeded the "
                    + "configured limit";

    public ContextOverflow {
        if (turnSequence < 1
                || firstTriggerBusSequence < 1
                || finalTriggerBusSequence < firstTriggerBusSequence) {
            throw new IllegalArgumentException(
                    "overflow turn correlation is invalid"
            );
        }
        if (configuredCharacterBudget < 1
                || mandatoryCharacterCount <= configuredCharacterBudget
                || overshootCharacters
                != mandatoryCharacterCount - configuredCharacterBudget) {
            throw new IllegalArgumentException(
                    "overflow sizing is inconsistent"
            );
        }
        largestMandatorySections = List.copyOf(Objects.requireNonNull(
                largestMandatorySections,
                "largestMandatorySections"
        ));
    }

    /** One mandatory section and the characters it consumed. */
    public record SectionWeight(String section, int characterCount) {

        public SectionWeight {
            Objects.requireNonNull(section, "section");
            if (section.isBlank()) {
                throw new IllegalArgumentException(
                        "section must not be blank"
                );
            }
            if (characterCount < 0) {
                throw new IllegalArgumentException(
                        "characterCount must be nonnegative"
                );
            }
        }
    }
}
