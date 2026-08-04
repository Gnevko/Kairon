package kairon.observation.journal.event.travel;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code Docked} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 4.2</a>
 */
public record Docked(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Docked";

    public Docked {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A ship docked at a station, outpost or settlement.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String station = LlmPresentableJournalEvent
                .displayText(event, "StationName")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unnamed station");
        List<String> locationFacts = new ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("StationType"))
                .ifPresent(value -> locationFacts.add(
                        "station type "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.textual(event.get("StarSystem"))
                .ifPresent(value -> locationFacts.add(
                        "system "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.decimal(event.get("DistFromStarLS"))
                .ifPresent(value -> locationFacts.add(
                        "distance from the arrival star "
                                + value
                                + " light-seconds"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(value -> locationFacts.add("market ID " + value));

        List<String> conditionFacts = new ArrayList<>();
        addTrueCondition(
                event,
                "CockpitBreach",
                "the cockpit was breached on landing",
                conditionFacts
        );
        addTrueCondition(
                event,
                "Wanted",
                "the player was wanted locally",
                conditionFacts
        );
        addTrueCondition(
                event,
                "ActiveFine",
                "the player had an active fine",
                conditionFacts
        );
        LlmPresentableJournalEvent.textual(event.get("StationState"))
                .ifPresent(value -> conditionFacts.add(
                        "station state "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Taxi"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> conditionFacts.add(
                        "the player arrived in a taxi"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Multicrew"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> conditionFacts.add(
                        "the player arrived aboard another player's vessel"
                ));

        List<String> sentences = new ArrayList<>();
        sentences.add(
                "The player completed docking at "
                        + station
                        + (locationFacts.isEmpty()
                        ? "."
                        : ", with "
                                + LlmPresentableJournalEvent.joinFacts(
                                        locationFacts
                                )
                                + ".")
        );
        if (!conditionFacts.isEmpty()) {
            sentences.add(
                    "At docking, the journal reports "
                            + LlmPresentableJournalEvent.joinFacts(
                                    conditionFacts
                            )
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }

    private static void addTrueCondition(
            JsonNode event,
            String field,
            String description,
            List<String> conditions
    ) {
        LlmPresentableJournalEvent.booleanValue(event.get(field))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> conditions.add(description));
    }
}
