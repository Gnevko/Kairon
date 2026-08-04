package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code InvitedToSquadron} journal event.
 */
public record InvitedToSquadron(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "InvitedToSquadron";

    public InvitedToSquadron {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}