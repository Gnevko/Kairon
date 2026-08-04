package kairon.observation.journal.event.combat;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code ShipTargeted} journal event.
 */
public record ShipTargeted(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "ShipTargeted";

    public ShipTargeted {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}