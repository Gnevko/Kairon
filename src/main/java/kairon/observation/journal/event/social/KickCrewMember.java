package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code KickCrewMember} journal event.
 */
public record KickCrewMember(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "KickCrewMember";

    public KickCrewMember {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}