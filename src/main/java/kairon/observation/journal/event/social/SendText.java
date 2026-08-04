package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code SendText} journal event.
 */
public record SendText(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "SendText";

    public SendText {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}