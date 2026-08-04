package kairon.observation.journal.event.mining;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ProspectedAsteroid} journal event.
 */
public record ProspectedAsteroid(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ProspectedAsteroid";

    public ProspectedAsteroid {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}