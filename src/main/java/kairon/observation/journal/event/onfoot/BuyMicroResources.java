package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code BuyMicroResources} journal event.
 */
public record BuyMicroResources(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "BuyMicroResources";

    public BuyMicroResources {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}