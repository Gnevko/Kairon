package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code LaunchSRV} journal event.
 */
public record LaunchSRV(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "LaunchSRV";

    public LaunchSRV {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}