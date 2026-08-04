package kairon.observation.journal.event.session;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Shutdown} journal event.
 */
public record Shutdown(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Shutdown";

    public Shutdown {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}