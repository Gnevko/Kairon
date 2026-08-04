package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code RestockVehicle} journal event.
 */
public record RestockVehicle(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "RestockVehicle";

    public RestockVehicle {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}