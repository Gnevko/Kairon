package kairon.observer.decision;

import java.util.Objects;
import java.util.Set;

/**
 * How one journal event type becomes a domain-facing event.
 *
 * <p>A rule, not a class: the projection itself is shared by mechanism, and
 * this only records the four things that genuinely differ per event type.</p>
 *
 * @param kind         the stable domain-facing name the model sees. Never the
 *                     journal wire name and never a Kairon graph node name.
 * @param mechanism    which mechanism reads this event: the family of game
 *                     thing it is, and which canonical changes an event of that
 *                     family already states.
 * @param readAs       how much of the current situation this event needs, when
 *                     the mechanism's default is wrong for it. Null — the
 *                     ordinary case — reads the mechanism's own profile.
 *                     Non-null is a claim about this event: a codex entry names
 *                     a body the journal contradicts, and an arrival in an
 *                     undiscovered system is about the system. Both are
 *                     exploration; neither reads a body. Setting this is how a
 *                     scope narrows without a new mechanism being invented for
 *                     it.
 * @param objectName   what to call the thing the event acts on, when the
 *                     entity kind's default name would mislead — a friend is
 *                     not "the commander". Null to use the default.
 * @param quantityName what the event's bare numeric quantity measures. Null
 *                     when the event has no meaningful bare quantity; an
 *                     unnamed number is then dropped rather than sent, because
 *                     an unnamed number is exactly what the model misread.
 * @param multiStage   whether this event belongs to a genuine multi-step
 *                     process, so that a FINAL stage and a true completion are
 *                     information rather than a constant.
 * @param wholeAction  whether the kind is the entire assertion. When it is, the
 *                     event carries no process position and no qualification of
 *                     a claim it never made — both would describe something the
 *                     kind already settles. Setting this is a claim about the
 *                     event that has to be defensible on its own; it is not a
 *                     tidiness switch.
 * @param settledGap   the one model-facing uncertainty this event must not
 *                     carry, because the same request answers it outright. Null
 *                     when every gap the event has is genuinely open. Narrower
 *                     than {@code wholeAction}, which drops all of them: this
 *                     names a single gap and leaves the rest alone, and like
 *                     {@code wholeAction} it has to be defensible per event
 *                     rather than set for tidiness.
 * @param uncountedOnBody
 *                     whether a body-scoped count of this kind cannot inform,
 *                     so the count is left off rather than sent. Two grounds,
 *                     each a claim about the event that has to be defensible.
 *                     Either a second identical result is not recorded at all,
 *                     so the count can only ever be one — and
 *                     {@code occurrenceOnBody: 1} beside a scan result reads as
 *                     a claim that this body has been scanned once, which is a
 *                     fact about the visit rather than about the reading. Or
 *                     the kind is scoped to the system and names no body, so
 *                     the body it would be counted at is only wherever the ship
 *                     happened to be — the occurrence carries the body the
 *                     graph had established at that moment, which for a jump or
 *                     a completed survey is the arrival star and not a place
 *                     the event is about.
 * @param stageSpecificOccurrences
 *                     whether the graph records each stage of this kind under
 *                     its own structural type, so that a body-scoped count of
 *                     "this event type here" counts one stage rather than the
 *                     kind. The count is then not a fact about the kind the
 *                     model is reading, and {@code occurrenceOnBody} is left off
 *                     rather than sent under a name that would be read as the
 *                     whole. Only meaningful where the kind spans stages at all.
 * @param retainedQualifiers
 *                     the attributes this kind keeps, when the adapter's fact
 *                     carries more than the kind is about. Empty — the ordinary
 *                     case — keeps everything the adapter produced. Non-empty is
 *                     a claim that the rest belong to a different assertion made
 *                     from the same record: one journal record can be read two
 *                     ways, and a milestone carrying the full measurement set of
 *                     the scan it was derived from reads as that scan. Never a
 *                     way to shorten an event for its own sake.
 */
public record DecisionEventRule(
        String kind,
        DecisionMechanism mechanism,
        DecisionContextProfile readAs,
        String objectName,
        String quantityName,
        boolean multiStage,
        boolean wholeAction,
        String settledGap,
        boolean uncountedOnBody,
        boolean stageSpecificOccurrences,
        Set<String> retainedQualifiers
) {

    public DecisionEventRule {
        retainedQualifiers = Set.copyOf(
                Objects.requireNonNull(retainedQualifiers, "retainedQualifiers")
        );
        Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        mechanism = Objects.requireNonNull(mechanism, "mechanism");
        if (readAs == mechanism.contextProfile()) {
            throw new IllegalArgumentException(
                    "an override that restates the mechanism's own profile "
                            + "says nothing"
            );
        }
        if (objectName != null && objectName.isBlank()) {
            throw new IllegalArgumentException(
                    "objectName must not be blank when present"
            );
        }
        if (quantityName != null && quantityName.isBlank()) {
            throw new IllegalArgumentException(
                    "quantityName must not be blank when present"
            );
        }
        if (settledGap != null && settledGap.isBlank()) {
            throw new IllegalArgumentException(
                    "settledGap must not be blank when present"
            );
        }
        if (wholeAction && settledGap != null) {
            throw new IllegalArgumentException(
                    "a wholeAction kind already carries no gap to settle"
            );
        }
        if (stageSpecificOccurrences && !multiStage) {
            throw new IllegalArgumentException(
                    "only a multi-stage kind can be counted per stage"
            );
        }
        if (uncountedOnBody && stageSpecificOccurrences) {
            throw new IllegalArgumentException(
                    "one reason to omit the count is enough"
            );
        }
        for (String retained : retainedQualifiers) {
            if (retained == null || retained.isBlank()) {
                throw new IllegalArgumentException(
                        "a retained attribute must be named"
                );
            }
        }
    }

    static DecisionEventRule of(String kind, DecisionMechanism mechanism) {
        return of(kind, mechanism, null);
    }

    static DecisionEventRule of(
            String kind,
            DecisionMechanism mechanism,
            String quantityName
    ) {
        return new DecisionEventRule(
                kind,
                mechanism,
                null,
                null,
                quantityName,
                false,
                false,
                null,
                false,
                false,
                Set.of()
        );
    }

    /**
     * How much of the situation this event is read against; see
     * {@link #readAs()}.
     */
    DecisionEventRule reading(DecisionContextProfile profile) {
        return new DecisionEventRule(
                kind,
                mechanism,
                Objects.requireNonNull(profile, "profile"),
                objectName,
                quantityName,
                multiStage,
                wholeAction,
                settledGap,
                uncountedOnBody,
                stageSpecificOccurrences,
                retainedQualifiers
        );
    }

    /**
     * The profile this event is actually read against.
     *
     * <p>The one answer the projection uses: the rule's own when it names one,
     * and the mechanism's default otherwise. Nothing downstream asks the
     * mechanism for a context slice, so the two cannot disagree.</p>
     */
    DecisionContextProfile contextProfile() {
        return readAs == null ? mechanism.contextProfile() : readAs;
    }

    DecisionEventRule named(String value) {
        return with(value, multiStage, wholeAction, settledGap,
                uncountedOnBody, stageSpecificOccurrences, retainedQualifiers);
    }

    DecisionEventRule staged() {
        return with(objectName, true, wholeAction, settledGap,
                uncountedOnBody, stageSpecificOccurrences, retainedQualifiers);
    }

    /** The kind settles the action; see {@link #wholeAction()}. */
    DecisionEventRule whole() {
        return with(objectName, multiStage, true, settledGap,
                uncountedOnBody, stageSpecificOccurrences, retainedQualifiers);
    }

    /** That one gap is answered elsewhere; see {@link #settledGap()}. */
    DecisionEventRule settling(String gapName) {
        return with(objectName, multiStage, wholeAction,
                Objects.requireNonNull(gapName, "gapName"),
                uncountedOnBody, stageSpecificOccurrences, retainedQualifiers);
    }

    /** A repeat is never recorded; see {@link #uncountedOnBody()}. */
    DecisionEventRule uncounted() {
        return with(objectName, multiStage, wholeAction, settledGap,
                true, stageSpecificOccurrences, retainedQualifiers);
    }

    /**
     * The graph counts each stage separately; see
     * {@link #stageSpecificOccurrences()}.
     */
    DecisionEventRule countedPerStage() {
        return with(objectName, multiStage, wholeAction, settledGap,
                uncountedOnBody, true, retainedQualifiers);
    }

    /**
     * The rest of the adapter's attributes belong to another assertion; see
     * {@link #retainedQualifiers()}.
     */
    DecisionEventRule retaining(String... names) {
        return with(objectName, multiStage, wholeAction, settledGap,
                uncountedOnBody, stageSpecificOccurrences, Set.of(names));
    }

    private DecisionEventRule with(
            String objectName,
            boolean multiStage,
            boolean wholeAction,
            String settledGap,
            boolean uncountedOnBody,
            boolean stageSpecificOccurrences,
            Set<String> retainedQualifiers
    ) {
        return new DecisionEventRule(
                kind,
                mechanism,
                readAs,
                objectName,
                quantityName,
                multiStage,
                wholeAction,
                settledGap,
                uncountedOnBody,
                stageSpecificOccurrences,
                retainedQualifiers
        );
    }
}
