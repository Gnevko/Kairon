package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code SquadronApplicationApproved} journal event.
 */
public record SquadronApplicationApproved(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "SquadronApplicationApproved";

    public SquadronApplicationApproved {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}