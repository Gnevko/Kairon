package kairon.observation.journal.event.powerplay;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code PowerplaySalary} journal event.
 */
public record PowerplaySalary(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "PowerplaySalary";

    public PowerplaySalary {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}