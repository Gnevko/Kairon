package kairon.behavior.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

public record EventOccurrenceId(@JsonValue String value)
        implements Comparable<EventOccurrenceId> {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public EventOccurrenceId {
        value = requireNonBlank(value, "value");
    }

    @Override
    public int compareTo(EventOccurrenceId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
