package kairon.observation.journal.event.session;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code Promotion} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, sections 13.32 and 15.1</a>
 */
public record Promotion(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    private static final List<String> COMBAT = List.of(
            "Harmless", "Mostly Harmless", "Novice", "Competent", "Expert",
            "Master", "Dangerous", "Deadly", "Elite"
    );
    private static final List<String> TRADE = List.of(
            "Penniless", "Mostly Penniless", "Peddler", "Dealer", "Merchant",
            "Broker", "Entrepreneur", "Tycoon", "Elite"
    );
    private static final List<String> EXPLORE = List.of(
            "Aimless", "Mostly Aimless", "Scout", "Surveyor", "Explorer",
            "Pathfinder", "Ranger", "Pioneer", "Elite"
    );
    private static final List<String> CQC = List.of(
            "Helpless", "Mostly Helpless", "Amateur", "Semi Professional",
            "Professional", "Champion", "Hero", "Legend", "Elite"
    );
    private static final List<String> FEDERATION = List.of(
            "None", "Recruit", "Cadet", "Midshipman", "Petty Officer",
            "Chief Petty Officer", "Warrant Officer", "Ensign", "Lieutenant",
            "Lt. Commander", "Post Commander", "Post Captain",
            "Rear Admiral", "Vice Admiral", "Admiral"
    );
    private static final List<String> EMPIRE = List.of(
            "None", "Outsider", "Serf", "Master", "Squire", "Knight", "Lord",
            "Baron", "Viscount", "Count", "Earl", "Marquis", "Duke",
            "Prince", "King"
    );
    private static final List<String> SOLDIER = List.of(
            "Defenceless", "Mostly Defenceless", "Rookie", "Soldier",
            "Gunslinger", "Warrior", "Gladiator", "Deadeye", "Elite"
    );
    private static final List<String> EXOBIOLOGIST = List.of(
            "Directionless", "Mostly Directionless", "Compiler", "Collector",
            "Cataloguer", "Taxonomist", "Ecologist", "Geneticist", "Elite"
    );

    public static final String EVENT_TYPE = "Promotion";

    public Promotion {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "The Commander's rank increased.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> promotions = new ArrayList<>();
        addRank(event, "Combat", "combat", COMBAT, promotions);
        addRank(event, "Trade", "trade", TRADE, promotions);
        addRank(event, "Explore", "exploration", EXPLORE, promotions);
        addRank(event, "CQC", "CQC", CQC, promotions);
        addRank(
                event,
                "Federation",
                "Federation navy",
                FEDERATION,
                promotions
        );
        addRank(event, "Empire", "Imperial navy", EMPIRE, promotions);
        addRank(event, "Soldier", "mercenary", SOLDIER, promotions);
        addRank(
                event,
                "Exobiologist",
                "exobiology",
                EXOBIOLOGIST,
                promotions
        );
        String sentence = promotions.isEmpty()
                ? "The player's rank increased, but this event contains no "
                        + "usable documented rank field."
                : "The player received "
                        + (promotions.size() == 1
                        ? "a rank promotion: "
                        : "rank promotions: ")
                        + LlmPresentableJournalEvent.joinFacts(promotions)
                        + ".";
        return new LlmEventPresentation(List.of(sentence));
    }

    private static void addRank(
            JsonNode event,
            String field,
            String label,
            List<String> documentedRanks,
            List<String> promotions
    ) {
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get(field))
                .ifPresent(value -> {
                    String rank = value < documentedRanks.size()
                            ? LlmPresentableJournalEvent.quoted(
                            documentedRanks.get(Math.toIntExact(value))
                    )
                            : "source rank "
                                    + LlmPresentableJournalEvent
                                    .formattedInteger(value);
                    promotions.add(label + " rank " + rank);
                });
    }
}
