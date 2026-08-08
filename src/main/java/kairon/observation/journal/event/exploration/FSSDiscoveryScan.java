package kairon.observation.journal.event.exploration;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
 * {@code FSSDiscoveryScan} journal event.
 *
 * <p>Not a model-eligible trigger: the honk opens no turn of its own. It
 * describes itself anyway because the behaviour graph records it as
 * {@code FSS_DISCOVERY_SCAN}, and a graph vertex can reach the model as a
 * remembered predecessor. A vertex the model can be shown has to be able to say
 * what it is, and saying it here rather than in the trajectory table is what
 * lets a test compare the two.</p>
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.1</a>
 */
public record FSSDiscoveryScan(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "FSSDiscoveryScan";

    public FSSDiscoveryScan {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A full spectrum system scan swept the star system.";
    }
}
