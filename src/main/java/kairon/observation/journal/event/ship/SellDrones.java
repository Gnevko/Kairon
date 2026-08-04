package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code SellDrones} journal event.
 */
public record SellDrones(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "SellDrones";

    public SellDrones {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}