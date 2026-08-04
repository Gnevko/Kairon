package kairon.observation.journal.event.combat;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code FactionKillBond} journal event.
 */
public record FactionKillBond(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "FactionKillBond";

    public FactionKillBond {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}