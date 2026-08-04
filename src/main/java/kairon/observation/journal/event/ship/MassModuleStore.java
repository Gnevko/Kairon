package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code MassModuleStore} journal event.
 */
public record MassModuleStore(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "MassModuleStore";

    public MassModuleStore {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}