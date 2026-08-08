package kairon.observation.journal.event.colonisation;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
 * {@code ColonisationContribution} journal event.
 *
 * @see <a href="https://schemas.edomh.nl/ColonisationContribution.html">
 * Pinned journal-catalogue event contract</a>
 */
public record ColonisationContribution(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ColonisationContribution";

    public ColonisationContribution {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "Materials were contributed to a colonisation effort.";
    }
}
