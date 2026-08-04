package kairon.observation.journal.event.travel;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code VehicleSwitch} journal event.
 */
public record VehicleSwitch(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "VehicleSwitch";

    public VehicleSwitch {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}