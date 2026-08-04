package kairon.observation.journal.event.colonisation;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
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

    @Override
    public LlmEventPresentation llmPresentation() {
        return new LlmEventPresentation(List.of(
                "A colonisation beacon was deployed.",
                "This event contains no system, body, owner, or beacon "
                        + "identifier."
        ));
    }
}
