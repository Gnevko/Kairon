package kairon.observer.decision;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import kairon.semantics.SemanticValue;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/**
 * Writes a decision request with an explicit property order.
 *
 * <p>Every property is written by name in a fixed sequence, so the order is a
 * property of this class rather than of a Jackson feature, an annotation or a
 * record's declaration order. Nothing is sorted alphabetically.</p>
 *
 * <p>Values are native JSON. A quantity is a number under a field name that
 * already says what it measures, an identity is its own value, and a symbol is
 * a string — there is no {@code {"type":..,"value":..}} envelope anywhere.</p>
 */
public final class JacksonDecisionRequestSerializer {

    private static final JsonFactory FACTORY = new JsonFactory();

    public String serialize(LlmDecisionRequest request) {
        Objects.requireNonNull(request, "request");
        StringWriter out = new StringWriter(2048);
        try (JsonGenerator json = FACTORY.createGenerator(out)) {
            json.writeStartObject();
            writeEvents(json, request.events());
            writeChanges(json, request.changes());
            writeContext(json, request.context());
            if (request.contextIncomplete()) {
                json.writeBooleanField("contextIncomplete", true);
            }
            json.writeEndObject();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return out.toString();
    }

    /** One section on its own, for measuring what a part of the turn costs. */
    public String serializeSection(LlmDecisionRequest request, String section) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(section, "section");
        StringWriter out = new StringWriter(1024);
        try (JsonGenerator json = FACTORY.createGenerator(out)) {
            json.writeStartObject();
            switch (section) {
                case DecisionSections.EVENTS ->
                        writeEvents(json, request.events());
                case DecisionSections.CHANGES ->
                        writeChanges(json, request.changes());
                case DecisionSections.CONTEXT ->
                        writeContext(json, request.context());
                default -> throw new IllegalArgumentException(
                        "unknown section: " + section
                );
            }
            json.writeEndObject();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- events

    private static void writeEvents(
            JsonGenerator json,
            List<LlmDecisionRequest.Event> events
    ) throws IOException {
        json.writeArrayFieldStart(DecisionSections.EVENTS);
        for (LlmDecisionRequest.Event event : events) {
            json.writeStartObject();
            // What happened, in the record's own words. Kairon's internal kind
            // and its local event id are both deliberately absent: the kind is
            // a name only this process shares, and the id is a correlation
            // handle the model can neither verify nor act on. The id still
            // exists on the record, keeps the array's order, and is what the
            // trace maps back to a bus sequence — it simply stops being sent.
            json.writeStringField("event", event.description());
            for (LlmDecisionRequest.Field field : event.fields()) {
                value(json, field.name(), field.value());
            }
            for (LlmDecisionRequest.Listing listing : event.listings()) {
                json.writeArrayFieldStart(listing.name());
                for (String value : listing.values()) {
                    json.writeString(value);
                }
                json.writeEndArray();
            }
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    // --------------------------------------------------------------- changes

    private static void writeChanges(
            JsonGenerator json,
            List<LlmDecisionRequest.Change> changes
    ) throws IOException {
        if (changes.isEmpty()) {
            return;
        }
        json.writeArrayFieldStart(DecisionSections.CHANGES);
        for (LlmDecisionRequest.Change change : changes) {
            json.writeStartObject();
            // The causing event's position is not written. It pointed at an
            // identity the events stopped carrying, so on the wire it was a
            // reference to nothing; inside Kairon it still says whose step a
            // change was, which is what the stale check reads it for.
            json.writeStringField("subject", change.subject());
            json.writeStringField("kind", change.kind());
            json.writeObjectFieldStart("fields");
            for (LlmDecisionRequest.FieldChange field : change.fields()) {
                json.writeObjectFieldStart(field.name());
                value(json, "before", field.before());
                value(json, "after", field.after());
                json.writeEndObject();
            }
            json.writeEndObject();
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    // --------------------------------------------------------------- context

    private static void writeContext(
            JsonGenerator json,
            List<LlmDecisionRequest.ContextGroup> context
    ) throws IOException {
        if (context.isEmpty()) {
            return;
        }
        json.writeObjectFieldStart(DecisionSections.CONTEXT);
        for (LlmDecisionRequest.ContextGroup group : context) {
            json.writeObjectFieldStart(group.name());
            for (LlmDecisionRequest.Field fact : group.facts()) {
                value(json, fact.name(), fact.value());
            }
            json.writeEndObject();
        }
        json.writeEndObject();
    }

    // --------------------------------------------------------------- writers

    /**
     * Native JSON for every semantic value; an unknown one writes nothing.
     *
     * <p>A quantity loses its unit here on purpose: the projection only names a
     * quantity field once the name states the unit, so writing it again would
     * be the {@code {amount, unit}} envelope under another name.</p>
     */
    private static void value(
            JsonGenerator json,
            String name,
            SemanticValue value
    ) throws IOException {
        if (value == null || !value.known()) {
            return;
        }
        switch (value) {
            case SemanticValue.TextValue text ->
                    json.writeStringField(name, text.value());
            case SemanticValue.SymbolicValue symbol ->
                    json.writeStringField(name, symbol.symbol());
            case SemanticValue.BooleanValue flag ->
                    json.writeBooleanField(name, flag.value());
            case SemanticValue.IntegralValue integral ->
                    json.writeNumberField(name, integral.value());
            case SemanticValue.DecimalValue decimal ->
                    json.writeNumberField(name, decimal.value());
            case SemanticValue.QuantityValue quantity ->
                    json.writeNumberField(name, quantity.amount());
            case SemanticValue.IdentityValue identity ->
                    json.writeStringField(name, identity.value());
            case SemanticValue.CoordinatesValue coordinates -> {
                json.writeArrayFieldStart(name);
                json.writeNumber(coordinates.latitude());
                json.writeNumber(coordinates.longitude());
                json.writeEndArray();
            }
            case SemanticValue.UnknownValue ignored -> {
                // Unreachable: known() already excluded it.
            }
        }
    }
}
