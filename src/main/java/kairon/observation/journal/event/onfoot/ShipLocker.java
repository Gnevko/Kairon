package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ShipLocker} journal event.
 */
public record ShipLocker(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ShipLocker";

    public ShipLocker {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}