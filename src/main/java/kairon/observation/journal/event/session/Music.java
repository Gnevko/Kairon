package kairon.observation.journal.event.session;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Music} journal event.
 */
public record Music(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Music";

    public Music {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}