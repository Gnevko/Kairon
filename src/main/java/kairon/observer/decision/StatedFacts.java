package kairon.observer.decision;

import kairon.observer.decision.DecisionEventProjector.ProjectedEvent;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticValue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything this turn has already said, under the identity it said it by.
 *
 * <p>Built once from the turn's projected events, and read the same way by
 * {@link DecisionChangeSelector} and {@link DecisionContextSelector}. Those two
 * used to answer "has this already been said?" with different machinery — one
 * compared canonical fields and values, the other compared slot names for
 * changes and <em>rendered strings</em> for events. A boolean or a number was
 * therefore never suppressed by the second, and a fact could appear in
 * {@code context} that an event beside it had already stated in so many
 * words.</p>
 *
 * <h2>What a statement is</h2>
 * <p>A canonical subject, a canonical field, the value, and the event that said
 * it. Both halves of the identity always: a value alone proved nothing, which is
 * how a landing reporting {@code occurrenceOnBody: 1} came to count as having
 * stated every canonical field whose value happened to be one.</p>
 *
 * <p>Two ways a fact gets in, and they are asked differently on purpose. An
 * event states a fact — subject, field, value and the event's own id. A change
 * occupies a slot: it names the same canonical field the context would and is
 * the better version, because it also says what the value was before. So a
 * change suppresses the slot whatever the value, and an event suppresses a fact
 * only when the value matches too.</p>
 *
 * <h2>What is not here</h2>
 * <p>Scope. A fact excluded because the turn is about another body, or because
 * no mechanism in it has business with the subject, was never <em>stated</em> —
 * it is simply not this turn's to send, and {@link DecisionBodyScope} and the
 * profile's subjects answer that. Keeping the two apart is what makes each
 * exclusion have exactly one reason.</p>
 */
final class StatedFacts {

    private final Set<String> statedSlots;
    private final List<Statement> statements;

    private StatedFacts(Set<String> statedSlots, List<Statement> statements) {
        this.statedSlots = Set.copyOf(statedSlots);
        this.statements = List.copyOf(statements);
    }

    /** What the turn's events say, before any change has been selected. */
    static StatedFacts ofEvents(List<ProjectedEvent> events) {
        Objects.requireNonNull(events, "events");
        Set<String> slots = new LinkedHashSet<>();
        List<Statement> stated = new ArrayList<>();
        for (ProjectedEvent event : events) {
            for (Map.Entry<String, SemanticValue> fact
                    : event.statedFacts().entrySet()) {
                String identity = fact.getKey();
                stated.add(new Statement(
                        identity,
                        fact.getValue(),
                        event.event().id()
                ));
                if (identity.indexOf('.') >= 0) {
                    // A slot-shaped key is a canonical field this event has
                    // already reported about the situation, not merely a name
                    // of its own.
                    slots.add(identity);
                }
            }
            // And the second canonical field one of the event's own fields
            // answers, where the mechanism declares the pairing. A jump names
            // the system it arrived in, and the arrival star is named after its
            // system — so that one field is the body's name too. A statement
            // rather than a slot, because it holds only at the value the event
            // actually said: a jump whose canonical body is still the previous
            // system's moon says nothing about that moon.
            for (LlmDecisionRequest.Field emitted : event.event().fields()) {
                SemanticField answered =
                        event.mechanism().alsoAnsweredBy(emitted.name());
                String slot = answered == null
                        ? null
                        : DecisionNames.slotOf(answered);
                if (slot != null) {
                    stated.add(new Statement(
                            slot,
                            emitted.value(),
                            event.event().id()
                    ));
                }
            }
            // And what the event's own sentence says without emitting a field
            // for it: "a ship entered supercruise" is the flight mode in words.
            // A statement and not a slot, for the same reason as above — it
            // holds at the value the sentence names and says nothing about a
            // mode that has since become something else.
            for (Map.Entry<String, SemanticValue> declared
                    : event.kindStatements().entrySet()) {
                stated.add(new Statement(
                        declared.getKey(),
                        declared.getValue(),
                        event.event().id()
                ));
            }
        }
        return new StatedFacts(slots, stated);
    }

    /**
     * The same facts, plus the slots this turn's changes have taken.
     *
     * <p>A change names the canonical field the context would name and says
     * more about it, so the context does not repeat it. The value is
     * deliberately not compared: a change says what the value <em>became</em>,
     * which is what the context would have said, and comparing them again would
     * only let a rounding or a rendering difference through.</p>
     */
    StatedFacts withChanges(List<LlmDecisionRequest.Change> changes) {
        Objects.requireNonNull(changes, "changes");
        Set<String> slots = new LinkedHashSet<>(statedSlots);
        for (LlmDecisionRequest.Change change : changes) {
            for (LlmDecisionRequest.FieldChange field : change.fields()) {
                slots.add(change.subject() + "." + field.name());
            }
        }
        return new StatedFacts(slots, statements);
    }

    /**
     * Whether an event of this turn already stated this canonical field at this
     * value.
     *
     * <p>The identity compared is the canonical field's own model-facing name,
     * or the slot it is reported under — the same two spellings the projection
     * emits. Where an event answers a canonical slot under another word, the
     * pairing is declared in {@link DecisionNames} rather than guessed.</p>
     */
    boolean statesFact(SemanticField field, SemanticValue value) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, "value");
        String name = DecisionNames.field(field);
        if (name == null) {
            return false;
        }
        String slot = DecisionNames.slotOf(field);
        for (Statement statement : statements) {
            if (!statement.value().equals(value)) {
                continue;
            }
            if (statement.identity().equals(slot)
                    || statement.identity().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this canonical slot has already been answered.
     *
     * <p>By an event that states the slot outright — a recovery says which
     * vessel came back, and the vehicle group would then say which one is
     * current, under the same word — or by a change that names the same field.
     * </p>
     */
    boolean statesSlot(String subject, String field) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(field, "field");
        return statedSlots.contains(subject + "." + field);
    }

    /** Whether this canonical field's slot has already been answered. */
    boolean statesSlot(SemanticField field) {
        String slot = DecisionNames.slotOf(Objects.requireNonNull(
                field,
                "field"
        ));
        return slot != null && statedSlots.contains(slot);
    }

    /**
     * One thing this turn says, and what said it.
     *
     * @param identity the canonical slot ({@code body.name}) when the fact
     *                 answers one, and the field's own model-facing name
     *                 otherwise
     * @param value    exactly what was said, as a typed semantic value — never
     *                 a rendering of it
     * @param eventId  the local id of the event that said it, which is what
     *                 makes a statement attributable rather than ambient
     */
    record Statement(String identity, SemanticValue value, Integer eventId) {

        Statement {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(value, "value");
        }
    }
}
