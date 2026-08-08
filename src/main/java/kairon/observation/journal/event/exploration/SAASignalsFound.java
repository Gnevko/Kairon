package kairon.observation.journal.event.exploration;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
/**
 * Typed identity and model-facing sentence for the Elite Dangerous
 * {@code SAASignalsFound} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.15</a>
 */
public record SAASignalsFound(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "SAASignalsFound";

    public SAASignalsFound {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    /**
     * Describes signal data without implying a non-empty {@code Signals} array.
     *
     * <p>{@code Signals} is required and typed as an array, but the schema has
     * no {@code minItems}.</p>
     */
    @Override
    public String modelFacingDescription() {
        return "A surface area analysis scan reported signal data for a "
                + "planet or rings.";
    }
}
