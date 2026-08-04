package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ModuleStore} journal event.
 */
public record ModuleStore(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ModuleStore";

    public ModuleStore {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}