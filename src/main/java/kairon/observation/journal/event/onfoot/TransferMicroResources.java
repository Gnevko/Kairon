package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code TransferMicroResources} journal event.
 */
public record TransferMicroResources(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "TransferMicroResources";

    public TransferMicroResources {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}