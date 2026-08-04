package kairon.observation.journal.event.carrier;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CarrierTradeOrder} journal event.
 */
public record CarrierTradeOrder(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CarrierTradeOrder";

    public CarrierTradeOrder {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}