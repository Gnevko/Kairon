package kairon.observation.journal.event.mission;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Missions} journal event.
 */
public record Missions(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Missions";

    public Missions {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}