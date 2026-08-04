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
 * {@code Liftoff} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 4.11</a>
 */
public record Liftoff(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Liftoff";

    public Liftoff {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> flightFacts = new ArrayList<>();
        LlmPresentableJournalEvent
                .booleanValue(event.get("PlayerControlled"))
                .ifPresent(value -> flightFacts.add(
                        value
                                ? "the player controlled the ship during "
                                        + "takeoff"
                                : "the unoccupied ship took off while the "
                                        + "player was in an SRV"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Taxi"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> flightFacts.add(
                        "the vessel was a taxi"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Multicrew"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> flightFacts.add(
                        "the vessel belonged to another player"
                ));

        List<String> locationFacts = new ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("StarSystem"))
                .ifPresent(value -> locationFacts.add(
                        "system "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.textual(event.get("Body"))
                .ifPresent(value -> locationFacts.add(
                        "body "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .displayText(event, "NearestDestination")
                .ifPresent(value -> locationFacts.add(
                        "nearest destination "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.decimal(event.get("Latitude"))
                .ifPresent(value -> locationFacts.add(
                        "latitude " + value + " degrees"
                ));
        LlmPresentableJournalEvent.decimal(event.get("Longitude"))
                .ifPresent(value -> locationFacts.add(
                        "longitude " + value + " degrees"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("OnStation"))
                .ifPresent(value -> locationFacts.add(
                        value ? "from a station" : "not from a station"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("OnPlanet"))
                .ifPresent(value -> locationFacts.add(
                        value ? "from a planet surface"
                                : "not from a planet surface"
                ));

        List<String> sentences = new ArrayList<>();
        sentences.add(
                "A ship took off"
                        + (flightFacts.isEmpty()
                        ? "."
                        : "; "
                                + LlmPresentableJournalEvent.joinFacts(
                                        flightFacts
                                )
                                + ".")
        );
        if (!locationFacts.isEmpty()) {
            sentences.add(
                    "The liftoff was recorded at "
                            + LlmPresentableJournalEvent.joinFacts(
                                    locationFacts
                            )
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
