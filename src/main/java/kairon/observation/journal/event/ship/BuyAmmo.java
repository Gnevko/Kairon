package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code BuyAmmo} journal event.
 */
public record BuyAmmo(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "BuyAmmo";

    public BuyAmmo {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}