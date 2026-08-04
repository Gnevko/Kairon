package kairon.observation.journal.event.ship;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code LaunchDrone} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.25</a>
 */
public record LaunchDrone(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "LaunchDrone";

    public LaunchDrone {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A drone or limpet was launched.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();
        sentences.add(
                LlmPresentableJournalEvent.textual(event.get("Type"))
                        .map(type -> "The player launched "
                                + sourceTypeDescription(type)
                                + ".")
                        .orElse(
                                "The player launched a limpet or drone whose "
                                        + "type is not reported."
                        )
        );
        sentences.add(
                "This event does not report whether the limpet or drone "
                        + "completed its task successfully."
        );
        return new LlmEventPresentation(sentences);
    }

    private static String sourceTypeDescription(String sourceType) {
        return switch (sourceType.toLowerCase(Locale.ROOT)) {
            case "hatchbreaker" -> "a hatch-breaker limpet";
            case "fueltransfer" -> "a fuel-transfer limpet";
            case "collection" -> "a collector limpet";
            case "prospector" -> "a prospector limpet";
            case "repair" -> "a repair limpet";
            case "research" -> "a research limpet";
            case "decontamination" -> "a decontamination limpet";
            case "recon" -> "a recon limpet";
            default -> "a limpet or drone identified by the journal as type "
                    + LlmPresentableJournalEvent.quoted(sourceType);
        };
    }
}
