package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code UseConsumable} journal event.
 */
public record UseConsumable(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "UseConsumable";

    public UseConsumable {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}