package kairon.observation.journal.event.exploration;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code DatalinkScan} journal event.
 */
public record DatalinkScan(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "DatalinkScan";

    public DatalinkScan {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}