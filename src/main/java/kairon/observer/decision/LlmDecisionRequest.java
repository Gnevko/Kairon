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
 * being sent. The {@link Event#id() event id} is one of them, and so is the
 * {@link Change#eventId() eventId} that points at it: together they number the
 * events {@code 1..n} and say which of them caused each change, and neither is
 * serialized. A number the model can neither verify nor act on is Kairon's own
 * bookkeeping, exactly like the schema version.</p>
 *
 * <p>The turn's trigger bus sequences carry the same ordering outside this
 * record, in the same order the events are in, so nothing is lost by keeping
 * the numbering internal.</p>
 *
 * <p>Nothing here is derived from Kairon's memory of the visit. There was one
 * such part — {@code trajectory}: the visit's recent events and the transition
 * model's forecast of the next one. It is gone. Across the measured runs not a
 * single comment rested on either half, while the forecast could and did
 * mislead — a first sampling scan predicted in the middle of a sequence, a
 * probability of one standing on one observation. What the graph knows now
 * reaches the model as {@code occurrenceOnBody} and nothing else.</p>
 */
public record LlmDecisionRequest(
        List<Event> events,
        List<Change> changes,
        List<ContextGroup> context,
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
     * <p>Exactly one event per trigger. {@code description} is the literal
     * sentence the event's own record supplies for what it reports, and it is
     * the only statement of identity the model receives. {@code fields} carries
     * only the attributes that mechanism actually has — no subject, no default
     * actor, no completion flag on an atomic action, no unnamed quantity.</p>
     *
     * <p>{@code kind} and {@code id} both stay here and are <strong>not
     * serialized</strong>. {@code kind} is Kairon's own name for the event and
     * the projection reads it and the tests name events by it — but a model
     * told both a name and a description would be told the same thing twice,
     * once in a vocabulary that means nothing outside this process. {@code id} is this
     * event's position in the turn, which the pipeline, the trace and the
     * change attribution all key on and the model has no use for.</p>
     */
    public record Event(
            int id,
            String kind,
            String description,
            List<Field> fields,
            List<Listing> listings
    ) {

        public Event(
                int id,
                String kind,
                String description,
                List<Field> fields
        ) {
            this(id, kind, description, fields, List.of());
        }

        public Event {
            if (id < 1) {
                throw new IllegalArgumentException("event id must be positive");
            }
            kind = requireNonBlank(kind, "kind");
            description = requireNonBlank(description, "description");
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
            listings = List.copyOf(
                    Objects.requireNonNull(listings, "listings")
            );
        }
    }

    /**
     * Several things of one sort that an event reported at once.
     *
     * <p>A surface scanner names the organisms it found, and there can be three
     * of them. That is one attribute with several values, which a
     * {@link Field} cannot be: {@link SemanticValue} is a closed set with no
     * list in it, and widening it would put the compound value ADR-0024 removed
     * back into every field in the document.</p>
     *
     * <p>So the shape is narrow on purpose. A listing carries plain names and
     * nothing else — no count beside each, no status, no nesting. What is known
     * about each of them is said elsewhere, under its own name, exactly as
     * before.</p>
     */
    public record Listing(String name, List<String> values) {

        public Listing {
            name = requireNonBlank(name, "name");
            values = List.copyOf(Objects.requireNonNull(values, "values"));
            if (values.isEmpty()) {
                throw new IllegalArgumentException(
                        "an empty listing is expressed by omitting it"
                );
            }
            for (String value : values) {
                requireNonBlank(value, "value");
            }
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
     * caused the change and absent when a hidden observation did. It is the
     * causing event's position in {@code events}, counting from one, and like
     * that position it is <strong>not serialized</strong>: a pointer is worth
     * only as much as what it points at, and the events carry no identity for
     * it to name.</p>
     *
     * <p>It stays on the record because two things read it, and neither is the
     * model. {@link DecisionChangeSelector} decides on it — a change one of
     * this request's own events caused is never reconciled against later state,
     * because its {@code eventId} says whose step it was — and the contract
     * tests read it to tell a background change from an attributed one before
     * anything is serialized. The internal bus sequence, wire event name,
     * selection role and write-path origin stay inside Kairon too.</p>
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
