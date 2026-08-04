package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ChangeCrewRole} journal event.
 */
public record ChangeCrewRole(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ChangeCrewRole";

    public ChangeCrewRole {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}