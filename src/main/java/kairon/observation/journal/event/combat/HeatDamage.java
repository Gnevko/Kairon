package kairon.observation.journal.event.combat;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code HeatDamage} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 5.8</a>
 */
public record HeatDamage(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "HeatDamage";

    public HeatDamage {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        return new LlmEventPresentation(List.of(
                "The player's current vessel took damage from overheating.",
                "This event does not report the damage amount, current heat "
                        + "level, or damaged modules."
        ));
    }
}
