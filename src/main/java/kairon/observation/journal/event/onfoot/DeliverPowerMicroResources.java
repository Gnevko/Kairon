package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code DeliverPowerMicroResources} journal event.
 */
public record DeliverPowerMicroResources(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "DeliverPowerMicroResources";

    public DeliverPowerMicroResources {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}