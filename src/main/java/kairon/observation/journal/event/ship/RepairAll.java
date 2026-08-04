package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code RepairAll} journal event.
 */
public record RepairAll(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "RepairAll";

    public RepairAll {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}