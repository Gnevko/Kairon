package kairon.observation.journal.event.session;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Progress} journal event.
 */
public record Progress(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Progress";

    public Progress {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}