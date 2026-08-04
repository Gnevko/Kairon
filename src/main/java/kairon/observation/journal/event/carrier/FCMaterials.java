package kairon.observation.journal.event.carrier;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code FCMaterials} journal event.
 */
public record FCMaterials(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "FCMaterials";

    public FCMaterials {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}