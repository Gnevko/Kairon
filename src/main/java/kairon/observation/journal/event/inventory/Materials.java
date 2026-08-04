package kairon.observation.journal.event.inventory;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Materials} journal event.
 */
public record Materials(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Materials";

    public Materials {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}