package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code SuitLoadout} journal event.
 */
public record SuitLoadout(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "SuitLoadout";

    public SuitLoadout {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}