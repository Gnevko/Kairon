package kairon.observation.journal.event.travel;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code JetConeDamage} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.22</a>
 */
public record JetConeDamage(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "JetConeDamage";

    public JetConeDamage {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String module = LlmPresentableJournalEvent
                .displayText(event, "Module")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified ship module");
        return new LlmEventPresentation(List.of(
                "Passing through a white-dwarf or neutron-star jet cone "
                        + "damaged ship module "
                        + module
                        + ".",
                "This event does not report the amount of module damage."
        ));
    }
}
