package kairon.observation.journal.event.engineering;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code EngineerProgress} journal event.
 */
public record EngineerProgress(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "EngineerProgress";

    public EngineerProgress {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}