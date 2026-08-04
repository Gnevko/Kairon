package kairon.observation.journal.event.trade;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code BuyTradeData} journal event.
 */
public record BuyTradeData(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "BuyTradeData";

    public BuyTradeData {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}