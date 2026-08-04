package kairon.observation.journal.event.travel;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code StartJump} journal event.
 */
public record StartJump(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "StartJump";

    public StartJump {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}