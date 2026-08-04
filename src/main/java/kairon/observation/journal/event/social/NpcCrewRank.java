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
 * {@code NpcCrewRank} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, sections 13.31 and 15.1</a>
 */
public record NpcCrewRank(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    private static final List<String> COMBAT_RANKS = List.of(
            "Harmless",
            "Mostly Harmless",
            "Novice",
            "Competent",
            "Expert",
            "Master",
            "Dangerous",
            "Deadly",
            "Elite"
    );

    public static final String EVENT_TYPE = "NpcCrewRank";

    public NpcCrewRank {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String crewMember = LlmPresentableJournalEvent
                .textual(event.get("NpcCrewName"))
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unnamed NPC crew member");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("NpcCrewId"))
                .ifPresent(value -> facts.add("NPC crew ID " + value));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("RankCombat"))
                .ifPresent(value -> facts.add(
                        "new combat rank " + combatRank(value)
                ));
        String sentence = "NPC crew member "
                + crewMember
                + " gained a combat rank"
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }

    private static String combatRank(long sourceRank) {
        return sourceRank < COMBAT_RANKS.size()
                ? LlmPresentableJournalEvent.quoted(
                        COMBAT_RANKS.get(Math.toIntExact(sourceRank))
                )
                : "source rank "
                        + LlmPresentableJournalEvent.formattedInteger(
                                sourceRank
                        );
    }
}
