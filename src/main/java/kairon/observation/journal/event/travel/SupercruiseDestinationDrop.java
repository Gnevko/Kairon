package kairon.observation.journal.event.travel;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code SupercruiseDestinationDrop} journal event.
 */
public record SupercruiseDestinationDrop(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "SupercruiseDestinationDrop";

    public SupercruiseDestinationDrop {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}