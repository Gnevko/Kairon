package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ClearImpound} journal event.
 */
public record ClearImpound(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ClearImpound";

    public ClearImpound {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}