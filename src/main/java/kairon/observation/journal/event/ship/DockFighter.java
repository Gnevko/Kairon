package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code DockFighter} journal event.
 */
public record DockFighter(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "DockFighter";

    public DockFighter {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}