package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code SquadronApplicationRejected} journal event.
 */
public record SquadronApplicationRejected(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "SquadronApplicationRejected";

    public SquadronApplicationRejected {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}