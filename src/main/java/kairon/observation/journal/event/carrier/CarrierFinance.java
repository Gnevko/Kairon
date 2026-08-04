package kairon.observation.journal.event.carrier;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CarrierFinance} journal event.
 */
public record CarrierFinance(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CarrierFinance";

    public CarrierFinance {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}