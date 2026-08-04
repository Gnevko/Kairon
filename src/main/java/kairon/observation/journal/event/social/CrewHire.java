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
 * {@code CrewHire} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, sections 8.10 and 15.1</a>
 */
public record CrewHire(RawJournalData raw)
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

    public static final String EVENT_TYPE = "CrewHire";

    public CrewHire {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String crewMember = LlmPresentableJournalEvent
                .textual(event.get("Name"))
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unnamed crew member");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("CrewID"))
                .ifPresent(value -> facts.add("crew ID " + value));
        LlmPresentableJournalEvent.textual(event.get("Faction"))
                .ifPresent(value -> facts.add(
                        "faction "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("Cost"))
                .ifPresent(value -> facts.add(
                        "hiring cost "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
                                + " credits"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("CombatRank"))
                .ifPresent(value -> facts.add(
                        "combat rank " + combatRank(value)
                ));
        String sentence = "The player hired crew member "
                + crewMember
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
