package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code SellMicroResources} journal event.
 */
public record SellMicroResources(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "SellMicroResources";

    public SellMicroResources {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}