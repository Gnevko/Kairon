package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CrewMemberRoleChange} journal event.
 */
public record CrewMemberRoleChange(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CrewMemberRoleChange";

    public CrewMemberRoleChange {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}