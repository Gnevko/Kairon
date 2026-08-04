package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ReservoirReplenished} journal event.
 */
public record ReservoirReplenished(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ReservoirReplenished";

    public ReservoirReplenished {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}