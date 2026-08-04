package kairon.observation.journal.event.powerplay;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code PowerplayDeliver} journal event.
 */
public record PowerplayDeliver(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "PowerplayDeliver";

    public PowerplayDeliver {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}