package kairon.observation.journal.event.exploration;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code FSSSignalDiscovered} journal event.
 */
public record FSSSignalDiscovered(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "FSSSignalDiscovered";

    public FSSSignalDiscovered {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}