package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ShipyardRedeem} journal event.
 */
public record ShipyardRedeem(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ShipyardRedeem";

    public ShipyardRedeem {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}