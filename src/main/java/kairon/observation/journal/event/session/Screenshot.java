package kairon.observation.journal.event.session;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Screenshot} journal event.
 */
public record Screenshot(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Screenshot";

    public Screenshot {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}