package kairon.observation.journal.event.session;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ClearSavedGame} journal event.
 */
public record ClearSavedGame(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ClearSavedGame";

    public ClearSavedGame {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}