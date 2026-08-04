package kairon.observation.journal.event.exploration;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code DiscoveryScan} journal event.
 */
public record DiscoveryScan(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "DiscoveryScan";

    public DiscoveryScan {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}