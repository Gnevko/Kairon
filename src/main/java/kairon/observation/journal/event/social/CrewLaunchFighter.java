package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CrewLaunchFighter} journal event.
 */
public record CrewLaunchFighter(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CrewLaunchFighter";

    public CrewLaunchFighter {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}