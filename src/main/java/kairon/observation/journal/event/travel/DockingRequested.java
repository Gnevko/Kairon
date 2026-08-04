package kairon.observation.journal.event.travel;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code DockingRequested} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 4.6</a>
 */
public record DockingRequested(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "DockingRequested";

    public DockingRequested {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String station = LlmPresentableJournalEvent
                .displayText(event, "StationName")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified station");
        List<String> stationFacts = new ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("StationType"))
                .ifPresent(value -> stationFacts.add(
                        "station type "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(value -> stationFacts.add("market ID " + value));

        List<String> sentences = new ArrayList<>();
        sentences.add(
                "The player requested docking at "
                        + station
                        + (stationFacts.isEmpty()
                        ? "."
                        : ", with "
                                + LlmPresentableJournalEvent.joinFacts(
                                        stationFacts
                                )
                                + ".")
        );
        landingPadCounts(event.get("LandingPads"))
                .ifPresent(counts -> sentences.add(
                        "The journal reports landing-pad counts of "
                                + counts
                                + "; these counts do not guarantee that a "
                                + "pad is currently available."
                ));
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> landingPadCounts(
            JsonNode landingPads
    ) {
        if (landingPads == null || !landingPads.isObject()) {
            return Optional.empty();
        }
        List<String> counts = new ArrayList<>();
        addPadCount(counts, landingPads, "Small", "small");
        addPadCount(counts, landingPads, "Medium", "medium");
        addPadCount(counts, landingPads, "Large", "large");
        return counts.isEmpty()
                ? Optional.empty()
                : Optional.of(
                        LlmPresentableJournalEvent.joinFacts(counts)
                );
    }

    private static void addPadCount(
            List<String> counts,
            JsonNode landingPads,
            String sourceField,
            String label
    ) {
        LlmPresentableJournalEvent
                .nonNegativeIntegral(landingPads.get(sourceField))
                .ifPresent(value -> counts.add(label + " " + value));
    }
}
