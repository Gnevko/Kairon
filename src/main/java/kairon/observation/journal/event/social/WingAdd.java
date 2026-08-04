package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code WingAdd} journal event.
 */
public record WingAdd(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "WingAdd";

    public WingAdd {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}