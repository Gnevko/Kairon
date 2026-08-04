package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code LoadoutRemoveModule} journal event.
 */
public record LoadoutRemoveModule(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "LoadoutRemoveModule";

    public LoadoutRemoveModule {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}