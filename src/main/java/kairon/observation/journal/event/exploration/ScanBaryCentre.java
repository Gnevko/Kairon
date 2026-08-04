package kairon.observation.journal.event.exploration;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ScanBaryCentre} journal event.
 */
public record ScanBaryCentre(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ScanBaryCentre";

    public ScanBaryCentre {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}