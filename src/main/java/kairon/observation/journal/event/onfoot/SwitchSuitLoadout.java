package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code SwitchSuitLoadout} journal event.
 */
public record SwitchSuitLoadout(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "SwitchSuitLoadout";

    public SwitchSuitLoadout {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}