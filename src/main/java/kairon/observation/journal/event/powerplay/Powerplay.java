package kairon.observation.journal.event.powerplay;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Powerplay} journal event.
 */
public record Powerplay(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Powerplay";

    public Powerplay {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}