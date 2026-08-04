package kairon.observation.journal.event.engineering;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code MaterialTrade} journal event.
 */
public record MaterialTrade(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "MaterialTrade";

    public MaterialTrade {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}