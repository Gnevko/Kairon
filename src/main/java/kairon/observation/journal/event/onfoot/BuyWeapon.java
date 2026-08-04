package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code BuyWeapon} journal event.
 */
public record BuyWeapon(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "BuyWeapon";

    public BuyWeapon {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}