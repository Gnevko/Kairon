package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import java.util.List;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
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
    public String modelFacingDescription() {
        return "A crew member's combat rank increased.";
    }
}
