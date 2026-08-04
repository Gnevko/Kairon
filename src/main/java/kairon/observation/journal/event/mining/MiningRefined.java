package kairon.observation.journal.event.mining;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code MiningRefined} journal event.
 */
public record MiningRefined(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "MiningRefined";

    public MiningRefined {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}