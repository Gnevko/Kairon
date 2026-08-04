package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CrewAssign} journal event.
 */
public record CrewAssign(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CrewAssign";

    public CrewAssign {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}