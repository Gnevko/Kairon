package kairon.observation.journal.event.mission;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Passengers} journal event.
 */
public record Passengers(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Passengers";

    public Passengers {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}