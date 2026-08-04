package kairon.observation.journal.event.travel;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code JetConeBoost} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.21</a>
 */
public record JetConeBoost(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "JetConeBoost";

    public JetConeBoost {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "Material collected from a star's jet cone gave the drive a boost.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String value = LlmPresentableJournalEvent
                .decimal(event.get("BoostValue"))
                .map(boost -> ", with reported jump-boost value " + boost)
                .orElse("");
        return new LlmEventPresentation(List.of(
                "The ship collected enough material in a white-dwarf or "
                        + "neutron-star jet cone to charge an FSD jump boost"
                        + value
                        + "."
        ));
    }
}
