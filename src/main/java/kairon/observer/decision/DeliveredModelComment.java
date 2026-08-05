package kairon.observer.decision;

import java.util.List;
import java.util.Objects;

/**
 * One comment that was actually delivered, with the correlation the delivering
 * turn already had in scope.
 *
 * <p>Kairon's own duplicate-comment memory and nothing else. It is never part
 * of a decision request: the model is not shown what it said before, and the
 * repetition check runs locally against this list after the response arrives.</p>
 *
 * <p>{@code turnSequence} and {@code triggerBusSequences} are retained because
 * they exist at the exact moment a comment is appended, and a later diagnosis
 * needs to know which turn and which observations a repeated sentence came out
 * of. The triggers are the whole batch the turn was built from, taken from the
 * batch: the model is shown no identity for an event and makes no claim about
 * which of them it answered.</p>
 *
 * <p>No topic, entity or process milestone is derived. Extracting those from
 * generated text would be pre-model interpretation, which the project
 * forbids.</p>
 */
public record DeliveredModelComment(
        long turnSequence,
        String text,
        List<Long> triggerBusSequences
) {

    public DeliveredModelComment {
        if (turnSequence < 1) {
            throw new IllegalArgumentException(
                    "turnSequence must be positive"
            );
        }
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        triggerBusSequences = List.copyOf(Objects.requireNonNull(
                triggerBusSequences,
                "triggerBusSequences"
        ));
    }
}
