package kairon.observation.journal.event.colonisation;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
 * {@code ColonisationConstructionDepot} journal event.
 *
 * @see <a href="https://schemas.edomh.nl/ColonisationConstructionDepot.html">
 * Pinned journal-catalogue event contract</a>
 */
public record ColonisationConstructionDepot(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ColonisationConstructionDepot";

    public ColonisationConstructionDepot {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A construction depot reported its progress while the ship was docked there.";
    }
}
