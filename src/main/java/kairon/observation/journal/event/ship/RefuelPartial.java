package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code RefuelPartial} journal event.
 */
public record RefuelPartial(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "RefuelPartial";

    public RefuelPartial {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}