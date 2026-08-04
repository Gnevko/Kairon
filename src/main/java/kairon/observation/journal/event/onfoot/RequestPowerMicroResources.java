package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code RequestPowerMicroResources} journal event.
 */
public record RequestPowerMicroResources(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "RequestPowerMicroResources";

    public RequestPowerMicroResources {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}