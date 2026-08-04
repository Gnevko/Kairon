package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CollectItems} journal event.
 */
public record CollectItems(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CollectItems";

    public CollectItems {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}