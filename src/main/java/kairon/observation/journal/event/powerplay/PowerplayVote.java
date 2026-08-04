package kairon.observation.journal.event.powerplay;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code PowerplayVote} journal event.
 */
public record PowerplayVote(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "PowerplayVote";

    public PowerplayVote {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}