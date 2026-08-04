package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ShipLockerMaterials} journal event.
 */
public record ShipLockerMaterials(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ShipLockerMaterials";

    public ShipLockerMaterials {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}