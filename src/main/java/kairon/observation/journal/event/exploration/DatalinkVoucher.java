package kairon.observation.journal.event.exploration;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code DatalinkVoucher} journal event.
 */
public record DatalinkVoucher(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "DatalinkVoucher";

    public DatalinkVoucher {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}