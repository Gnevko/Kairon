package kairon.observation.journal.event.session;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import java.util.List;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
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
}
