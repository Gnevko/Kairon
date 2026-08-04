package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code BuySuit} journal event.
 */
public record BuySuit(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "BuySuit";

    public BuySuit {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}