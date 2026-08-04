package kairon.observation.journal.event.travel;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code BookDropship} journal event.
 */
public record BookDropship(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "BookDropship";

    public BookDropship {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}