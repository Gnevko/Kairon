package kairon.observation.journal.event.exploration;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code DataScanned} journal event.
 */
public record DataScanned(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "DataScanned";

    public DataScanned {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}