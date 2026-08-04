package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code JoinACrew} journal event.
 */
public record JoinACrew(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "JoinACrew";

    public JoinACrew {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}