package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code StoredModules} journal event.
 */
public record StoredModules(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "StoredModules";

    public StoredModules {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}