package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code SquadronStartup} journal event.
 */
public record SquadronStartup(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "SquadronStartup";

    public SquadronStartup {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}