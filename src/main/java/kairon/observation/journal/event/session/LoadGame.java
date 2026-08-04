package kairon.observation.journal.event.session;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code LoadGame} journal event.
 */
public record LoadGame(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "LoadGame";

    public LoadGame {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}