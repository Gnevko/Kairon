package kairon.observation.journal.event.travel;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CancelDropship} journal event.
 */
public record CancelDropship(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CancelDropship";

    public CancelDropship {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}