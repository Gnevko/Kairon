package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ModuleInfo} journal event.
 */
public record ModuleInfo(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ModuleInfo";

    public ModuleInfo {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}