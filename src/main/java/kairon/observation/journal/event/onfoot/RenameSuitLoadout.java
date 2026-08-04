package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code RenameSuitLoadout} journal event.
 */
public record RenameSuitLoadout(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "RenameSuitLoadout";

    public RenameSuitLoadout {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}