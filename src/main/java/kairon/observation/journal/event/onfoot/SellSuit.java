package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code SellSuit} journal event.
 */
public record SellSuit(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "SellSuit";

    public SellSuit {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}