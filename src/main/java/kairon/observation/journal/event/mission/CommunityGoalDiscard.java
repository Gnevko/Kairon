package kairon.observation.journal.event.mission;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code CommunityGoalDiscard} journal event.
 */
public record CommunityGoalDiscard(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "CommunityGoalDiscard";

    public CommunityGoalDiscard {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}