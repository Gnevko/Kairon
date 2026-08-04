package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code WingInvite} journal event.
 */
public record WingInvite(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "WingInvite";

    public WingInvite {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}