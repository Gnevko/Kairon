package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code DropItems} journal event.
 */
public record DropItems(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "DropItems";

    public DropItems {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}