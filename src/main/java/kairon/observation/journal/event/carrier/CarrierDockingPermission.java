package kairon.observation.journal.event.carrier;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CarrierDockingPermission} journal event.
 */
public record CarrierDockingPermission(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CarrierDockingPermission";

    public CarrierDockingPermission {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}