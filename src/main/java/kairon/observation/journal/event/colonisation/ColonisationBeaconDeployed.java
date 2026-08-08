package kairon.observation.journal.event.colonisation;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
 * {@code ColonisationBeaconDeployed} journal event.
 *
 * @see <a href="https://schemas.edomh.nl/ColonisationBeaconDeployed.html">
 * Pinned journal-catalogue event contract</a>
 */
public record ColonisationBeaconDeployed(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ColonisationBeaconDeployed";

    public ColonisationBeaconDeployed {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A colonisation beacon was deployed.";
    }
}
