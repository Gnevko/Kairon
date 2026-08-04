package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ModuleSellRemote} journal event.
 */
public record ModuleSellRemote(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ModuleSellRemote";

    public ModuleSellRemote {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}