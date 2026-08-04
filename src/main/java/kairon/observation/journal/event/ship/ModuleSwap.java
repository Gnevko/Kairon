package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ModuleSwap} journal event.
 */
public record ModuleSwap(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ModuleSwap";

    public ModuleSwap {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}