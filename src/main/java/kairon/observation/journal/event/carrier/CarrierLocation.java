package kairon.observation.journal.event.carrier;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CarrierLocation} journal event.
 */
public record CarrierLocation(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CarrierLocation";

    public CarrierLocation {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}