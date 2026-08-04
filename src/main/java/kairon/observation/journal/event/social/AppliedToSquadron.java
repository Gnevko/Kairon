package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code AppliedToSquadron} journal event.
 */
public record AppliedToSquadron(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "AppliedToSquadron";

    public AppliedToSquadron {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}