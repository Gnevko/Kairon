package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Shipyard} journal event.
 */
public record Shipyard(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Shipyard";

    public Shipyard {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}