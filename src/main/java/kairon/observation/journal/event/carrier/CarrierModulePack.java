package kairon.observation.journal.event.carrier;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CarrierModulePack} journal event.
 */
public record CarrierModulePack(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CarrierModulePack";

    public CarrierModulePack {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}