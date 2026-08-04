package kairon.observer.decision;

import kairon.observation.journal.JournalEventObservation;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * A domain rule a record earns rather than a type.
 *
 * <p>Almost every journal class means one thing, and the class-keyed table is
 * the whole of the per-event decision for those. A few records mean something
 * their class cannot say: one {@code Scan} is a body established, and the
 * shallow reading of the star a visit arrived at, reporting that nobody had
 * been here, is a different assertion made from the same record.</p>
 *
 * <p>That used to be a single {@code if} inside the lookup, which made it
 * invisible to anything that asked the catalogue what rules exist — a property
 * asserted of "every rule" could not see it, and a second such case would have
 * been a second {@code if} whose precedence was whichever was written first.
 * Declaring it is what makes both answerable: the rules are enumerable, and a
 * record matching two of them is an error rather than a race between two
 * branches.</p>
 *
 * @param name      what this rule claims, for the message when two of them
 *                  claim the same record
 * @param predicate whether this record earns the rule. Read from the record's
 *                  own fields — never from adjacency, timing or anything the
 *                  pipeline knows and the record does not
 * @param rule      the domain rule the record earns
 */
public record RecordDecisionRule(
        String name,
        Predicate<JournalEventObservation> predicate,
        DecisionEventRule rule
) {

    public RecordDecisionRule {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        predicate = Objects.requireNonNull(predicate, "predicate");
        rule = Objects.requireNonNull(rule, "rule");
    }

    /** Whether this record earns the rule. */
    public boolean matches(JournalEventObservation event) {
        return predicate.test(Objects.requireNonNull(event, "event"));
    }
}
