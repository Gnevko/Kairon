package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ModuleBuy} journal event.
 */
public record ModuleBuy(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ModuleBuy";

    public ModuleBuy {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}