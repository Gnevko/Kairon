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
 * {@code SquadronPromotion} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 10.10</a>
 */
public record SquadronPromotion(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "SquadronPromotion";

    public SquadronPromotion {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String squadron = LlmPresentableJournalEvent
                .textual(event.get("SquadronName"))
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unnamed squadron");
        List<String> facts = rankFacts(event);
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("SquadronID"))
                .ifPresent(value -> facts.add("squadron ID " + value));
        String sentence = "The player's rank in squadron "
                + squadron
                + " was promoted"
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }

    private static List<String> rankFacts(JsonNode event) {
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "OldRankName")
                .ifPresent(value -> facts.add(
                        "previous rank name "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("OldRank"))
                .ifPresent(value -> facts.add(
                        "previous source rank " + value
                ));
        LlmPresentableJournalEvent.displayText(event, "NewRankName")
                .ifPresent(value -> facts.add(
                        "new rank name "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("NewRank"))
                .ifPresent(value -> facts.add("new source rank " + value));
        return facts;
    }
}
