package kairon.observation.journal.event.powerplay;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code PowerplayMerits} journal event.
 */
public record PowerplayMerits(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "PowerplayMerits";

    public PowerplayMerits {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}