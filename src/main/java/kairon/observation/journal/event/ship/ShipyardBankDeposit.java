package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ShipyardBankDeposit} journal event.
 */
public record ShipyardBankDeposit(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ShipyardBankDeposit";

    public ShipyardBankDeposit {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}