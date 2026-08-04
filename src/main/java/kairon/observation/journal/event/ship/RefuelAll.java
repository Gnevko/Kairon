package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code RefuelAll} journal event.
 */
public record RefuelAll(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "RefuelAll";

    public RefuelAll {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}