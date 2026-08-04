package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code DisbandedSquadron} journal event.
 */
public record DisbandedSquadron(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "DisbandedSquadron";

    public DisbandedSquadron {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}