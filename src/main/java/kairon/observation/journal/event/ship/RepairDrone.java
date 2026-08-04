package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code RepairDrone} journal event.
 */
public record RepairDrone(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "RepairDrone";

    public RepairDrone {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}