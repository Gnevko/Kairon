package kairon.observation.journal.event.session;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code Fileheader} journal event.
 */
public record Fileheader(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Fileheader";

    public Fileheader {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}