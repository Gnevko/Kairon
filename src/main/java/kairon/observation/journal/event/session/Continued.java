package kairon.observation.journal.event.session;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Continued} journal event.
 */
public record Continued(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Continued";

    public Continued {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}