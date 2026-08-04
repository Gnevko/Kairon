package kairon.semantics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One structured statement derived from a single observation.
 *
 * <p>Carries the meaning as typed primitives so that subject, operation,
 * identity, quantity, process stage, completion and negation survive
 * independently of any prose. {@code presentation} may accompany a fact as a
 * human-readable summary, but it is never the authority and is never parsed
 * back.</p>
 *
 * @param subject         which canonical subject the statement is about. Never
 *                        null: a fact with no subject has nothing to attach to.
 * @param operation       what happened, from the closed operation vocabulary.
 *                        Polarity belongs here first — {@code FAILED} and
 *                        {@code LEFT} are operations, not negated operations.
 * @param actor           who performed the operation, when the event names a
 *                        subject rather than a thing. Null when unestablished.
 * @param object          what the operation was performed on, when that is a
 *                        thing rather than a subject. Null when unestablished.
 * @param identity        the identifier the event carries for the statement,
 *                        typically the same value as {@code object.id()}.
 *                        {@link SemanticValue#unknown()} when the event carries
 *                        none; never null.
 * @param quantity        how much, with its unit when the event supplies one.
 *                        {@link SemanticValue#unknown()} when absent.
 * @param qualifiers      further event-supplied attributes, keyed by a stable
 *                        name. Insertion-ordered and immutable. Only known
 *                        values are retained, so an absent key means the event
 *                        did not establish it.
 * @param processStage    where this observation sits in a multi-step process.
 *                        {@code NOT_APPLICABLE} when the event is not part of
 *                        one — that is a statement, not a gap.
 * @param completion      whether the operation completed. {@code TRUE} means
 *                        completed, {@code FALSE} means explicitly did not, and
 *                        null means the event does not establish it.
 * @param negation        <strong>an explicit negative assertion only.</strong>
 *                        {@code TRUE} exactly when the event itself asserts a
 *                        field false ({@code BoostUsed: false}), reports a
 *                        denial, or reports that an attempt did not succeed. It
 *                        is <strong>not</strong> a marker for an operation that
 *                        reverses a paired positive one: undocking, leaving a
 *                        body, recovering an SRV and quitting a wing are
 *                        positively completed actions and carry
 *                        {@code completion: true} with no negation. Express the
 *                        reversal through {@code operation} and
 *                        {@code relationship} instead. The prompt lets an
 *                        explicit negation outrank a prediction, so a completed
 *                        positive action labelled {@code negation: true} is a
 *                        structural misreading.
 * @param relationship    free-form statement of how this fact relates to a
 *                        paired earlier one ({@code "negates JoinedSquadron"}).
 *                        The only channel for reversal. Null when there is no
 *                        pair; never blank.
 * @param assertionSource whether the game reported the fact or Kairon derived
 *                        it. Never null; {@code REPORTED} is the default.
 * @param presentation    optional human-readable summary. Never authoritative,
 *                        never parsed back, never outranks the typed
 *                        components. Null when the fact needs none; never
 *                        blank.
 * @param provenance      which observation this statement came from. Never
 *                        null: a fact with no provenance cannot be audited.
 */
public record SemanticFact(
        SemanticSubject subject,
        SemanticOperation operation,
        SemanticSubject actor,
        EntityRef object,
        SemanticValue identity,
        SemanticValue quantity,
        Map<String, SemanticValue> qualifiers,
        ProcessStage processStage,
        Boolean completion,
        Boolean negation,
        String relationship,
        AssertionSource assertionSource,
        String presentation,
        SemanticProvenance provenance
) {

    public SemanticFact {
        subject = Objects.requireNonNull(subject, "subject");
        operation = Objects.requireNonNull(operation, "operation");
        identity = Objects.requireNonNull(identity, "identity");
        quantity = Objects.requireNonNull(quantity, "quantity");
        processStage = Objects.requireNonNull(processStage, "processStage");
        assertionSource = Objects.requireNonNull(
                assertionSource,
                "assertionSource"
        );
        provenance = Objects.requireNonNull(provenance, "provenance");
        qualifiers = immutableQualifiers(qualifiers);
        if (relationship != null && relationship.isBlank()) {
            throw new IllegalArgumentException(
                    "relationship must not be blank when present"
            );
        }
        if (presentation != null && presentation.isBlank()) {
            throw new IllegalArgumentException(
                    "presentation must not be blank when present"
            );
        }
    }

    /**
     * What a fact is about, when that is a thing rather than a subject.
     *
     * <p>{@code kind} is mandatory so an unattributed identifier can never be
     * silently bound to the wrong sort of entity.</p>
     */
    public record EntityRef(
            EntityKind kind,
            SemanticValue id,
            String name
    ) {

        public EntityRef {
            kind = Objects.requireNonNull(kind, "kind");
            id = Objects.requireNonNull(id, "id");
            if (name != null && name.isBlank()) {
                throw new IllegalArgumentException(
                        "entity name must not be blank when present"
                );
            }
        }

        public static EntityRef named(EntityKind kind, String name) {
            return new EntityRef(kind, SemanticValue.unknown(), name);
        }
    }

    /** The sort of thing an {@link EntityRef} points at. */
    public enum EntityKind {
        SYSTEM,
        BODY,
        STATION,
        SHIP,
        AUXILIARY_VEHICLE,
        COMMANDER,
        ORGANIC,
        CODEX_ENTRY,
        MISSION,
        COMMODITY,
        MESSAGE,
        FLEET_CARRIER,
        CONSTRUCTION_SITE,
        FACTION,
        POWER,
        SQUADRON,
        WING,
        CREW_MEMBER,
        ENGINEER,
        BLUEPRINT,
        SUIT,
        WEAPON,
        MATERIAL,
        SIGNAL_SOURCE,
        RANK,
        /** The kind could not be established and must not be guessed. */
        UNRESOLVED
    }

    /** Where an observation sits in a multi-step process. */
    public enum ProcessStage {
        START,
        PROGRESS,
        FINAL,
        NOT_APPLICABLE
    }

    /** Whether the game reported the fact or Kairon derived it. */
    public enum AssertionSource {
        REPORTED,
        DERIVED
    }

    private static Map<String, SemanticValue> immutableQualifiers(
            Map<String, SemanticValue> supplied
    ) {
        Objects.requireNonNull(supplied, "qualifiers");
        LinkedHashMap<String, SemanticValue> copy = new LinkedHashMap<>();
        supplied.forEach((key, value) -> {
            Objects.requireNonNull(key, "qualifier key");
            Objects.requireNonNull(value, "qualifier value");
            if (key.isBlank()) {
                throw new IllegalArgumentException(
                        "qualifier key must not be blank"
                );
            }
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    /** Minimal fluent builder; keeps adapters readable and explicit. */
    public static final class Builder {

        private final SemanticSubject subject;
        private final SemanticOperation operation;
        private final SemanticProvenance provenance;
        private final Map<String, SemanticValue> qualifiers =
                new LinkedHashMap<>();

        private SemanticSubject actor;
        private EntityRef object;
        private SemanticValue identity = SemanticValue.unknown();
        private SemanticValue quantity = SemanticValue.unknown();
        private ProcessStage processStage = ProcessStage.NOT_APPLICABLE;
        private Boolean completion;
        private Boolean negation;
        private String relationship;
        private AssertionSource assertionSource = AssertionSource.REPORTED;
        private String presentation;

        public Builder(
                SemanticSubject subject,
                SemanticOperation operation,
                SemanticProvenance provenance
        ) {
            this.subject = Objects.requireNonNull(subject, "subject");
            this.operation = Objects.requireNonNull(operation, "operation");
            this.provenance = Objects.requireNonNull(
                    provenance,
                    "provenance"
            );
        }

        public Builder actor(SemanticSubject value) {
            this.actor = value;
            return this;
        }

        public Builder object(EntityRef value) {
            this.object = value;
            return this;
        }

        public Builder identity(SemanticValue value) {
            this.identity = Objects.requireNonNull(value, "identity");
            return this;
        }

        public Builder quantity(SemanticValue value) {
            this.quantity = Objects.requireNonNull(value, "quantity");
            return this;
        }

        public Builder qualifier(String key, SemanticValue value) {
            if (value != null && value.known()) {
                qualifiers.put(key, value);
            }
            return this;
        }

        public Builder processStage(ProcessStage value) {
            this.processStage = Objects.requireNonNull(value, "processStage");
            return this;
        }

        public Builder completion(Boolean value) {
            this.completion = value;
            return this;
        }

        public Builder negation(Boolean value) {
            this.negation = value;
            return this;
        }

        public Builder relationship(String value) {
            this.relationship = value;
            return this;
        }

        public Builder assertionSource(AssertionSource value) {
            this.assertionSource = Objects.requireNonNull(
                    value,
                    "assertionSource"
            );
            return this;
        }

        public Builder presentation(String value) {
            this.presentation = value;
            return this;
        }

        public SemanticFact build() {
            return new SemanticFact(
                    subject,
                    operation,
                    actor,
                    object,
                    identity,
                    quantity,
                    qualifiers,
                    processStage,
                    completion,
                    negation,
                    relationship,
                    assertionSource,
                    presentation,
                    provenance
            );
        }
    }
}
