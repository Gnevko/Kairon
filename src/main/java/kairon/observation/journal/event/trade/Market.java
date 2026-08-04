package kairon.observation.journal.event.trade;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Market} journal event.
 */
public record Market(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Market";

    public Market {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}