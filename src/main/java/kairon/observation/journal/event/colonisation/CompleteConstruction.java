package kairon.observation.journal.event.colonisation;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
 * {@code CompleteConstruction} journal event.
 *
 * @see <a href="https://schemas.edomh.nl/CompleteConstruction.html">
 * Pinned journal-catalogue event contract</a>
 */
public record CompleteConstruction(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "CompleteConstruction";

    public CompleteConstruction {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A colonisation construction was completed.";
    }
}
