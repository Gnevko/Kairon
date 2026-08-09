package kairon.observer.decision;

import kairon.semantics.SemanticField;
import kairon.semantics.SemanticValue;

import java.util.LinkedHashMap;
import java.util.Map;
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
 * @param retainedQualifiers
 *                     the attributes this kind keeps, when the adapter's fact
 *                     carries more than the kind is about. Empty — the ordinary
 *                     case — keeps everything the adapter produced. Non-empty is
 *                     a claim that the rest do not belong to this assertion, on
 *                     one of two grounds. Either they belong to a different
 *                     assertion made from the same record — one journal record
 *                     can be read two ways, and a milestone carrying the full
 *                     measurement set of the scan it was derived from reads as
 *                     that scan. Or they are not facts about what happened at
 *                     all: a codex entry's category and region are the game's
 *                     own rubric, filed in the client's language, and the region
 *                     standing beside {@code newEntry} was read as a claim about
 *                     other regions that the document never made. Never a way to
 *                     shorten an event for its own sake.
 * @param unnamedObject
 *                     whether the thing this event acts on is not named to the
 *                     model at all. The ordinary case is false: an event names
 *                     what it acted on. True is a claim that the adapter's name
 *                     for it is not a name — a launched vehicle carries the
 *                     journal's {@code Loadout} string, {@code "base"}, because
 *                     the record has no vessel name in it, and a field reading
 *                     {@code loadout: "base"} is an internal token in the one
 *                     slot that says what was acted on. The identity is still
 *                     recorded everywhere else; only the model-facing name goes.
 * @param namesOrganisms
 *                     whether this reading names the organisms it found, so the
 *                     event lists them. True for the surface scanner and false
 *                     for the system scanner, which reports how many signals a
 *                     body carries and never which. Declared per event because
 *                     it is a fact about the instrument: the names are read off
 *                     the body in the registry snapshot captured with this very
 *                     observation, so an instrument that named nothing must not
 *                     list what another one named earlier.
 * @param reportsSampleValue
 *                     whether this reading says what the sample it finished is
 *                     worth. True for the analysis that completes a sampling
 *                     sequence and false for every other step, because only the
 *                     last one collects anything. See ADR-0029.
 * @param statedValues canonical fields this event's own sentence already states,
 *                     each at the one value it states. A supercruise entry says
 *                     the flight mode is supercruise in those words, so
 *                     {@code context.navigation.flightMode: SUPERCRUISE} beside
 *                     it is the same sentence twice. Different from the
 *                     mechanism's {@code causedFields}, which say what an event
 *                     of the family <em>moves</em> without claiming the sentence
 *                     names the result: a completed jump also leaves the ship in
 *                     supercruise, and its sentence does not say so, so the
 *                     current mode is still worth stating there. Read against
 *                     the value like {@code alsoAnswered} and for the same
 *                     reason — an event that says supercruise says nothing about
 *                     a mode that is no longer supercruise. Empty is the
 *                     ordinary case; a non-empty entry is a claim about the
 *                     wording of one class's description, and moves when that
 *                     wording moves.
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
        Set<String> retainedQualifiers,
        boolean unnamedObject,
        boolean namesOrganisms,
        boolean reportsSampleValue,
        Map<SemanticField, SemanticValue> statedValues
) {

    public DecisionEventRule {
        retainedQualifiers = Set.copyOf(
                Objects.requireNonNull(retainedQualifiers, "retainedQualifiers")
        );
        statedValues = Map.copyOf(
                Objects.requireNonNull(statedValues, "statedValues")
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
        for (String retained : retainedQualifiers) {
            if (retained == null || retained.isBlank()) {
                throw new IllegalArgumentException(
                        "a retained attribute must be named"
                );
            }
        }
        for (Map.Entry<SemanticField, SemanticValue> statement
                : statedValues.entrySet()) {
            if (statement.getKey() == null || statement.getValue() == null) {
                throw new IllegalArgumentException(
                        "a stated value needs both the field and the value"
                );
            }
            if (DecisionNames.slotOf(statement.getKey()) == null) {
                throw new IllegalArgumentException(
                        "a field the model is never sent cannot be stated: "
                                + statement.getKey()
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
                Set.of(),
                false,
                false,
                false,
                Map.of()
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
                retainedQualifiers,
                unnamedObject,
                namesOrganisms,
                reportsSampleValue,
                statedValues
        );
    }

    /**
     * This event's sentence already says this canonical field is now that; see
     * {@link #statedValues()}.
     *
     * <p>The value is given as the canonical enum constant rather than a string,
     * so a declaration cannot outlive the constant it names.</p>
     */
    DecisionEventRule stating(SemanticField field, Enum<?> value) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, "value");
        Map<SemanticField, SemanticValue> stated =
                new LinkedHashMap<>(statedValues);
        stated.put(field, SemanticValue.ofSymbol(value.name()));
        return new DecisionEventRule(
                kind,
                mechanism,
                readAs,
                objectName,
                quantityName,
                multiStage,
                wholeAction,
                settledGap,
                retainedQualifiers,
                unnamedObject,
                namesOrganisms,
                reportsSampleValue,
                stated
        );
    }

    /** This reading names what it found; see {@link #namesOrganisms()}. */
    DecisionEventRule namingOrganisms() {
        return new DecisionEventRule(
                kind,
                mechanism,
                readAs,
                objectName,
                quantityName,
                multiStage,
                wholeAction,
                settledGap,
                retainedQualifiers,
                unnamedObject,
                true,
                reportsSampleValue,
                statedValues
        );
    }

    /**
     * This reading says what the sample it finished is worth; see
     * {@link #reportsSampleValue()}.
     *
     * <p>Declared per event, not per mechanism. Sampling has three steps and
     * only the last one collects anything: a log and a sample in the middle of
     * a sequence have nothing to pay out, and a price on them would be a price
     * on work not yet done.</p>
     */
    DecisionEventRule reportingSampleValue() {
        return new DecisionEventRule(
                kind,
                mechanism,
                readAs,
                objectName,
                quantityName,
                multiStage,
                wholeAction,
                settledGap,
                retainedQualifiers,
                unnamedObject,
                namesOrganisms,
                true,
                statedValues
        );
    }

    /** The object's only name is an internal token; see {@link #unnamedObject()}. */
    DecisionEventRule unnamed() {
        return new DecisionEventRule(
                kind,
                mechanism,
                readAs,
                objectName,
                quantityName,
                multiStage,
                wholeAction,
                settledGap,
                retainedQualifiers,
                true,
                namesOrganisms,
                reportsSampleValue,
                statedValues
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
                retainedQualifiers);
    }

    DecisionEventRule staged() {
        return with(objectName, true, wholeAction, settledGap,
                retainedQualifiers);
    }

    /** The kind settles the action; see {@link #wholeAction()}. */
    DecisionEventRule whole() {
        return with(objectName, multiStage, true, settledGap,
                retainedQualifiers);
    }

    /** That one gap is answered elsewhere; see {@link #settledGap()}. */
    DecisionEventRule settling(String gapName) {
        return with(objectName, multiStage, wholeAction,
                Objects.requireNonNull(gapName, "gapName"),
                retainedQualifiers);
    }

    /**
     * The rest of the adapter's attributes belong to another assertion; see
     * {@link #retainedQualifiers()}.
     */
    DecisionEventRule retaining(String... names) {
        return with(objectName, multiStage, wholeAction, settledGap,
                Set.of(names));
    }

    private DecisionEventRule with(
            String objectName,
            boolean multiStage,
            boolean wholeAction,
            String settledGap,
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
                retainedQualifiers,
                unnamedObject,
                namesOrganisms,
                reportsSampleValue,
                statedValues
        );
    }
}
