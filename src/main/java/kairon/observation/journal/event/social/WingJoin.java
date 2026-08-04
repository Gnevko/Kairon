package kairon.observation.journal.event.social;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code WingJoin} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.50</a>
 */
public record WingJoin(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "WingJoin";

    public WingJoin {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode othersNode = raw.parsedJsonObject().get("Others");
        List<String> others = new ArrayList<>();
        if (othersNode != null && othersNode.isArray()) {
            for (JsonNode member : othersNode) {
                LlmPresentableJournalEvent.textual(member)
                        .map(LlmPresentableJournalEvent::quoted)
                        .ifPresent(others::add);
            }
        }
        String sentence = "The player joined a wing"
                + (others.isEmpty()
                ? "."
                : " whose other recorded "
                        + (others.size() == 1 ? "member was " : "members were ")
                        + LlmPresentableJournalEvent.joinFacts(others)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }
}
