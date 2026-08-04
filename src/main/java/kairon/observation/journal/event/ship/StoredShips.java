package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code StoredShips} journal event.
 */
public record StoredShips(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "StoredShips";

    public StoredShips {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}