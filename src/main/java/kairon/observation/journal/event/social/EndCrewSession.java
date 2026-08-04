package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code EndCrewSession} journal event.
 */
public record EndCrewSession(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "EndCrewSession";

    public EndCrewSession {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}