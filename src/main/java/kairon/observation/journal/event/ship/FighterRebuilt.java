package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code FighterRebuilt} journal event.
 */
public record FighterRebuilt(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "FighterRebuilt";

    public FighterRebuilt {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}