package kairon.observation.journal.event.session;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
 * {@code Commander} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 3.3</a>
 */
public record Commander(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Commander";

    public Commander {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    /**
     * The journal writes this when a Commander takes up a session — the one
     * Commander this ship has, arriving at the controls. The earlier sentence
     * described the file instead ("the session being loaded identified its
     * Commander"), and it read as telemetry about a load step rather than as
     * someone coming aboard.
     */
    @Override
    public String modelFacingDescription() {
        return "The Commander came aboard, and this session began.";
    }
}
