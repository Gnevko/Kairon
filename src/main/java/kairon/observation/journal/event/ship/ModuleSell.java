package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ModuleSell} journal event.
 */
public record ModuleSell(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ModuleSell";

    public ModuleSell {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}