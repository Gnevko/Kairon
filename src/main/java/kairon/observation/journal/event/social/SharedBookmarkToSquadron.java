package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code SharedBookmarkToSquadron} journal event.
 */
public record SharedBookmarkToSquadron(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "SharedBookmarkToSquadron";

    public SharedBookmarkToSquadron {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}