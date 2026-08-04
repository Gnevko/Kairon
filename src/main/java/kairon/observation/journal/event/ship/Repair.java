package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Repair} journal event.
 */
public record Repair(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Repair";

    public Repair {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}