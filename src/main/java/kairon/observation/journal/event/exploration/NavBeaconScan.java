package kairon.observation.journal.event.exploration;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code NavBeaconScan} journal event.
 */
public record NavBeaconScan(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "NavBeaconScan";

    public NavBeaconScan {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}