package kairon.observation.journal.event.carrier;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CarrierCrewServices} journal event.
 */
public record CarrierCrewServices(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CarrierCrewServices";

    public CarrierCrewServices {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}