package kairon.observation.journal.event.travel;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CancelTaxi} journal event.
 */
public record CancelTaxi(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CancelTaxi";

    public CancelTaxi {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}