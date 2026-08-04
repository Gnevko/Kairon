package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code BuyDrones} journal event.
 */
public record BuyDrones(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "BuyDrones";

    public BuyDrones {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}