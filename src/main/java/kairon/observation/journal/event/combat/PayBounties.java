package kairon.observation.journal.event.combat;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code PayBounties} journal event.
 */
public record PayBounties(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "PayBounties";

    public PayBounties {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}