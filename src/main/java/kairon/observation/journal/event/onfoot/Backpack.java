package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Backpack} journal event.
 */
public record Backpack(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Backpack";

    public Backpack {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}