package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code SellWeapon} journal event.
 */
public record SellWeapon(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "SellWeapon";

    public SellWeapon {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}