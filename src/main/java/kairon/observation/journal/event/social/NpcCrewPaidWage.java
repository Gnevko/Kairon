package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code NpcCrewPaidWage} journal event.
 */
public record NpcCrewPaidWage(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "NpcCrewPaidWage";

    public NpcCrewPaidWage {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}