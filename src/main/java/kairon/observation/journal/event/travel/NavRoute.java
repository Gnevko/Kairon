package kairon.observation.journal.event.travel;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code NavRoute} journal event.
 */
public record NavRoute(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "NavRoute";

    public NavRoute {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}