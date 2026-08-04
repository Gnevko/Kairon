package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code TradeMicroResources} journal event.
 */
public record TradeMicroResources(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "TradeMicroResources";

    public TradeMicroResources {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}