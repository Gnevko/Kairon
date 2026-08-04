package kairon.observation.journal.event.mission;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CommunityGoal} journal event.
 */
public record CommunityGoal(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CommunityGoal";

    public CommunityGoal {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}