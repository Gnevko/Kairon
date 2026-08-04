package kairon.observation.journal.event.carrier;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CarrierBankTransfer} journal event.
 */
public record CarrierBankTransfer(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CarrierBankTransfer";

    public CarrierBankTransfer {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}