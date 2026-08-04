package kairon.observation.journal.event.carrier;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CarrierStats} journal event.
 */
public record CarrierStats(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CarrierStats";

    public CarrierStats {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}