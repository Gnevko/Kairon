package kairon.observation.journal.event.session;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Reputation} journal event.
 */
public record Reputation(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Reputation";

    public Reputation {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}