package kairon.observation.journal.event.carrier;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CarrierDepositFuel} journal event.
 */
public record CarrierDepositFuel(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CarrierDepositFuel";

    public CarrierDepositFuel {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}