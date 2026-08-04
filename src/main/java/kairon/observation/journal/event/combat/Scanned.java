package kairon.observation.journal.event.combat;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Scanned} journal event.
 */
public record Scanned(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Scanned";

    public Scanned {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}