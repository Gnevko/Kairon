package kairon.observation.journal.event.onfoot;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
 * {@code HoloscreenHacked} journal event.
 *
 * @see <a href="https://github.com/jixxed/ed-journal-schemas/blob/33a8f35e81868b168b4bbd647b5e13dbd8de062a/schemas/HoloscreenHacked/HoloscreenHacked.json">
 * Pinned journal schema and observed-field descriptions</a>
 */
public record HoloscreenHacked(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "HoloscreenHacked";

    public HoloscreenHacked {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A holoscreen was hacked.";
    }
}
