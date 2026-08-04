package kairon.observation.journal.event.exploration;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code FSSDiscoveryScan} journal event.
 */
public record FSSDiscoveryScan(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "FSSDiscoveryScan";

    public FSSDiscoveryScan {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}