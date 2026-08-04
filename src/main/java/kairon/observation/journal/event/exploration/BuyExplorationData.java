package kairon.observation.journal.event.exploration;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code BuyExplorationData} journal event.
 */
public record BuyExplorationData(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "BuyExplorationData";

    public BuyExplorationData {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}