package kairon.observation.journal.event.travel;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ApproachSettlement} journal event.
 */
public record ApproachSettlement(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ApproachSettlement";

    public ApproachSettlement {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}