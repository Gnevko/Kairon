package kairon.observation.journal.event.colonisation;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
 * {@code ColonisationSystemClaim} journal event.
 *
 * @see <a href="https://schemas.edomh.nl/ColonisationSystemClaim.html">
 * Pinned journal-catalogue event contract</a>
 */
public record ColonisationSystemClaim(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ColonisationSystemClaim";

    public ColonisationSystemClaim {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A star system was claimed for colonisation.";
    }
}
