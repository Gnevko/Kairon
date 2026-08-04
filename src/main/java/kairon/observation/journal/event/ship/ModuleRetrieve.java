package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ModuleRetrieve} journal event.
 */
public record ModuleRetrieve(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ModuleRetrieve";

    public ModuleRetrieve {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}