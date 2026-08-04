package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code LoadoutEquipModule} journal event.
 */
public record LoadoutEquipModule(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "LoadoutEquipModule";

    public LoadoutEquipModule {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}