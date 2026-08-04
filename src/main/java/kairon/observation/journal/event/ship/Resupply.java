package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Resupply} journal event.
 */
public record Resupply(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Resupply";

    public Resupply {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}