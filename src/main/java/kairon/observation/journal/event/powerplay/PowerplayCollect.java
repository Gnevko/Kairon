package kairon.observation.journal.event.powerplay;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code PowerplayCollect} journal event.
 */
public record PowerplayCollect(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "PowerplayCollect";

    public PowerplayCollect {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}