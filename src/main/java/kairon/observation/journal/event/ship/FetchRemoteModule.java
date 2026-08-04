package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code FetchRemoteModule} journal event.
 */
public record FetchRemoteModule(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "FetchRemoteModule";

    public FetchRemoteModule {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}