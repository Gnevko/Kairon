package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Outfitting} journal event.
 */
public record Outfitting(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Outfitting";

    public Outfitting {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}