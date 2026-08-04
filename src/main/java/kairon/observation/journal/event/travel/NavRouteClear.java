package kairon.observation.journal.event.travel;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code NavRouteClear} journal event.
 */
public record NavRouteClear(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "NavRouteClear";

    public NavRouteClear {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}