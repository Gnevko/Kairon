package kairon.observation.journal.event.travel;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code BookTaxi} journal event.
 */
public record BookTaxi(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "BookTaxi";

    public BookTaxi {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}