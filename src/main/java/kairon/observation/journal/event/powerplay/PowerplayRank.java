package kairon.observation.journal.event.powerplay;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code PowerplayRank} journal event.
 *
 * @see <a href="https://github.com/jixxed/ed-journal-schemas/blob/33a8f35e81868b168b4bbd647b5e13dbd8de062a/schemas/PowerplayRank/PowerplayRank.json">
 * Pinned journal schema and observed-field descriptions</a>
 */
public record PowerplayRank(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "PowerplayRank";

    public PowerplayRank {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "The Commander ranked up with a power.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String power = LlmPresentableJournalEvent.textual(event.get("Power"))
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unnamed power");
        String rank = LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("Rank"))
                .map(value -> "source rank "
                        + LlmPresentableJournalEvent.formattedInteger(value))
                .orElse("an unreported rank");
        return new LlmEventPresentation(List.of(
                "The player's Powerplay rank with "
                        + power
                        + " increased to "
                        + rank
                        + "; this event does not define a narrative "
                        + "significance for that rank."
        ));
    }
}
