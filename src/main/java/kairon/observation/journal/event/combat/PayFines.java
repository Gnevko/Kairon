package kairon.observation.journal.event.combat;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code PayFines} journal event.
 */
public record PayFines(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "PayFines";

    public PayFines {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}