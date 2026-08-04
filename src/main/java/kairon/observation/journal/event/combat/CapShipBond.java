package kairon.observation.journal.event.combat;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CapShipBond} journal event.
 */
public record CapShipBond(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CapShipBond";

    public CapShipBond {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}