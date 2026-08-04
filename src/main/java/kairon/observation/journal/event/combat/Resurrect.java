package kairon.observation.journal.event.combat;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Resurrect} journal event.
 */
public record Resurrect(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Resurrect";

    public Resurrect {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}