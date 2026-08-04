package kairon.observation.journal.event.exploration;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ScientificResearch} journal event.
 */
public record ScientificResearch(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ScientificResearch";

    public ScientificResearch {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}