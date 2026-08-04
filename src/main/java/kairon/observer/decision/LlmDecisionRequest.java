package kairon.observer.decision;

import kairon.semantics.SemanticValue;

import java.util.List;
import java.util.Objects;

/**
 * Everything the provider is given for one decision, and nothing else.
 *
 * <p>Five parts, in decision order: what just happened, what it changed, what
 * else is true that the events do not say, what led here and usually follows,
 * and whether anything relevant was lost. Each part after the first is absent
 * when it carries nothing the model needs.</p>
 *
 * <p>Nothing here identifies Kairon to the model. There is no schema version,
 * no turn, no bus sequence, no timestamp, no source role and no raw event name.
 * Those all still exist inside the pipeline and in the trace; they simply stop
 * being sent. The one identifier the model does see is an {@link Event#id()
 * event id}, which is local to a single request and is mapped back to a bus
 * sequence by {@link DecisionEvidence}.</p>
 *
 * <p>{@link Trajectory} is the one part derived from Kairon's memory of this
 * system visit rather than from the current observations. It is projected the
 * same way everything else is: domain names, no identities, no counters, no
 * cursor — what happened, not what recorded it.</p>
 */
public record LlmDecisionRequest(
        List<Event> events,
        List<Change> changes,
        List<ContextGroup> context,
        Trajectory trajectory,
        boolean contextIncomplete
) {

    /**
     * The trace's name for this contract.
     *
     * <p>Recorded in the turn trace and never serialized into the provider
     * input: a version the model cannot act on is Kairon's own bookkeeping.</p>
     */
    public static final String CONTEXT_SCHEMA = "kairon-llm-decision-v1";

    public LlmDecisionRequest {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        context = List.copyOf(Objects.requireNonNull(context, "context"));
        if (events.isEmpty()) {
            throw new IllegalArgumentException(
                    "a decision request always carries at least one event"
            );
        }
        int expected = 1;
        for (Event event : events) {
            if (event.id() != expected++) {
                throw new IllegalArgumentException(
                        "event ids must run 1..n in order"
                );
            }
        }
    }

    /**
     * One current trigger, as a domain statement.
     *
     * <p>Exactly one event per trigger. {@code kind} names the game mechanism
     * rather than the journal wire name, and {@code fields} carries only the
     * attributes that mechanism actually has — no subject, no default actor, no
     * completion flag on an atomic action, no unnamed quantity.</p>
     */
    public record Event(int id, String kind, List<Field> fields) {

        public Event {
            if (id < 1) {
                throw new IllegalArgumentException("event id must be positive");
            }
            kind = requireNonBlank(kind, "kind");
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        }
    }

    /** One named domain attribute. Absent rather than null when unknown. */
    public record Field(String name, SemanticValue value) {

        public Field {
            name = requireNonBlank(name, "name");
            value = Objects.requireNonNull(value, "value");
            if (!value.known()) {
                throw new IllegalArgumentException(
                        "an unknown value is expressed by omitting the field"
                );
            }
        }
    }

    /**
     * A canonical change worth knowing about, attributed to its cause.
     *
     * <p>{@code eventId} is present when one of this request's own events
     * caused the change and absent when a hidden observation did — which is the
     * only thing the model is told about that observation. The internal bus
     * sequence, wire event name, selection role and write-path origin stay
     * inside Kairon.</p>
     */
    public record Change(
            Integer eventId,
            String subject,
            String kind,
            List<FieldChange> fields
    ) {

        public Change {
            if (eventId != null && eventId < 1) {
                throw new IllegalArgumentException(
                        "event id must be positive when present"
                );
            }
            subject = requireNonBlank(subject, "subject");
            kind = requireNonBlank(kind, "kind");
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
            if (fields.isEmpty()) {
                throw new IllegalArgumentException(
                        "a change without fields changed nothing"
                );
            }
        }
    }

    /**
     * The exact before and after of one canonical field.
     *
     * <p>{@code before} is omitted when the value was not previously known.
     * Neither side is ever approximated.</p>
     */
    public record FieldChange(
            String name,
            SemanticValue before,
            SemanticValue after
    ) {

        public FieldChange {
            name = requireNonBlank(name, "name");
            before = Objects.requireNonNull(before, "before");
            after = Objects.requireNonNull(after, "after");
            if (!after.known()) {
                throw new IllegalArgumentException(
                        "a model-facing change always has a known after value"
                );
            }
        }
    }

    /**
     * Where this turn sits in the run of events, both ways.
     *
     * <p>{@code recent} is fact: up to three events that actually happened
     * before these ones, oldest first, in the same vocabulary the events use.
     * {@code likelyNext} is not fact — it is what has tended to follow, and the
     * prompt says so in as many words. Keeping them in one object is deliberate:
     * they are the same subject seen backwards and forwards, and separating them
     * would invite reading the forecast as another list of events.</p>
     *
     * <p>Both may be empty; the whole object is absent when both are.</p>
     */
    public record Trajectory(List<String> recent, List<Prediction> likelyNext) {

        public Trajectory {
            recent = List.copyOf(Objects.requireNonNull(recent, "recent"));
            likelyNext = List.copyOf(
                    Objects.requireNonNull(likelyNext, "likelyNext")
            );
            if (recent.isEmpty() && likelyNext.isEmpty()) {
                throw new IllegalArgumentException(
                        "an empty trajectory is expressed by omitting it"
                );
            }
            for (String kind : recent) {
                requireNonBlank(kind, "recent kind");
            }
        }
    }

    /**
     * One expected next event and how often it has followed.
     *
     * <p>The probability is the transition model's own number, carried
     * unchanged. Nothing that supports it travels with it: an evidence count or
     * a context bucket beside a probability is an invitation to re-derive a
     * figure the model was already handed.</p>
     */
    public record Prediction(String kind, double probability) {

        public Prediction {
            kind = requireNonBlank(kind, "kind");
            if (!Double.isFinite(probability)
                    || probability < 0.0
                    || probability > 1.0) {
                throw new IllegalArgumentException(
                        "probability must be between zero and one"
                );
            }
        }
    }

    /**
     * One subject's currently established facts, selected for these events.
     *
     * <p>Subjects stay separated exactly as the projection separates them: a
     * vehicle associated with the Commander is never merged into where the
     * Commander is standing.</p>
     */
    public record ContextGroup(String name, List<Field> facts) {

        public ContextGroup {
            name = requireNonBlank(name, "name");
            facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
            if (facts.isEmpty()) {
                throw new IllegalArgumentException(
                        "an empty context group says nothing"
                );
            }
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
