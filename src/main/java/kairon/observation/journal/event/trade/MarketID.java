package kairon.observation.journal.event.trade;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code MarketID} journal event.
 */
public record MarketID(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "MarketID";

    public MarketID {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}