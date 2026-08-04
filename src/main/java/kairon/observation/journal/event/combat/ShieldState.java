package kairon.observation.journal.event.combat;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ShieldState} journal event.
 */
public record ShieldState(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ShieldState";

    public ShieldState {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}