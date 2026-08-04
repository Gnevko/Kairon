package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CreateSuitLoadout} journal event.
 */
public record CreateSuitLoadout(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CreateSuitLoadout";

    public CreateSuitLoadout {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}