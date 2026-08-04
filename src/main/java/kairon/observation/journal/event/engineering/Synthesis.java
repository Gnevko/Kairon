package kairon.observation.journal.event.engineering;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Synthesis} journal event.
 */
public record Synthesis(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Synthesis";

    public Synthesis {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}