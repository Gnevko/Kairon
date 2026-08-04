package kairon.observation.journal.event.powerplay;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code PowerplayVoucher} journal event.
 */
public record PowerplayVoucher(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "PowerplayVoucher";

    public PowerplayVoucher {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}