package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Loadout} journal event.
 */
public record Loadout(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Loadout";

    public Loadout {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}