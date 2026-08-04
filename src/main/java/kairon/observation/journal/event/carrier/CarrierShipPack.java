package kairon.observation.journal.event.carrier;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CarrierShipPack} journal event.
 */
public record CarrierShipPack(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CarrierShipPack";

    public CarrierShipPack {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}