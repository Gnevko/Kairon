package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code BackpackChange} journal event.
 */
public record BackpackChange(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "BackpackChange";

    public BackpackChange {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}