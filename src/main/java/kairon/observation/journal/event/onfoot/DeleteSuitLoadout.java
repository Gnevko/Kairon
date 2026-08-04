package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code DeleteSuitLoadout} journal event.
 */
public record DeleteSuitLoadout(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "DeleteSuitLoadout";

    public DeleteSuitLoadout {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}