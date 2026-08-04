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
 * {@code Undocked} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 4.17</a>
 */
public record Undocked(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Undocked";

    public Undocked {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> locationFacts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "StationName")
                .ifPresent(value -> locationFacts.add(
                        "station "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.displayText(event, "StationType")
                .ifPresent(value -> locationFacts.add(
                        "station type "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(value -> locationFacts.add(
                        "market ID " + value
                ));

        List<String> vesselFacts = new ArrayList<>();
        LlmPresentableJournalEvent.booleanValue(event.get("Taxi"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> vesselFacts.add(
                        "the vessel was a taxi"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Multicrew"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> vesselFacts.add(
                        "the vessel belonged to another player"
                ));

        StringBuilder departure = new StringBuilder(
                "A vessel lifted off from a landing pad"
        );
        if (!locationFacts.isEmpty()) {
            departure.append(" at ")
                    .append(LlmPresentableJournalEvent.joinFacts(
                            locationFacts
                    ));
        } else {
            departure.append(" at a station, outpost, or settlement");
        }
        departure.append('.');

        List<String> sentences = new ArrayList<>();
        sentences.add(departure.toString());
        if (!vesselFacts.isEmpty()) {
            sentences.add(
                    "The journal reports that "
                            + LlmPresentableJournalEvent.joinFacts(vesselFacts)
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
