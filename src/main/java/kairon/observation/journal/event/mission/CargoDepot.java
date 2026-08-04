package kairon.observation.journal.event.mission;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CargoDepot} journal event.
 */
public record CargoDepot(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CargoDepot";

    public CargoDepot {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}