package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code AfmuRepairs} journal event.
 */
public record AfmuRepairs(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "AfmuRepairs";

    public AfmuRepairs {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}