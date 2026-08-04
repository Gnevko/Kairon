package kairon.observation.journal.event.combat;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

/**
 * Neutral typed identity for the Elite Dangerous {@code HeatWarning} journal event.
 */
public record HeatWarning(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "HeatWarning";

    public HeatWarning {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }
}