package kairon.observation.journal.event.powerplay;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code PowerplayFastTrack} journal event.
 */
public record PowerplayFastTrack(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "PowerplayFastTrack";

    public PowerplayFastTrack {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}