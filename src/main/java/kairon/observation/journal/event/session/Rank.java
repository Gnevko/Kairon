package kairon.observation.journal.event.session;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Rank} journal event.
 */
public record Rank(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Rank";

    public Rank {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}