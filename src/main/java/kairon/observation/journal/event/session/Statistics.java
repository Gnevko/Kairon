package kairon.observation.journal.event.session;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Statistics} journal event.
 */
public record Statistics(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Statistics";

    public Statistics {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}