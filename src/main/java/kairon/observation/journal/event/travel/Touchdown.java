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
 * {@code Touchdown} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 4.16</a>
 */
public record Touchdown(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Touchdown";

    public Touchdown {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A ship landed on the surface of a planet or moon.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> landingFacts = new ArrayList<>();
        LlmPresentableJournalEvent
                .booleanValue(event.get("PlayerControlled"))
                .ifPresent(value -> landingFacts.add(
                        value
                                ? "the player controlled the ship during "
                                        + "landing"
                                : "the unoccupied ship landed after being "
                                        + "recalled while the player was in "
                                        + "an SRV"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Taxi"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> landingFacts.add(
                        "the vessel was a taxi"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Multicrew"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> landingFacts.add(
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
                        value ? "on a station" : "not on a station"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("OnPlanet"))
                .ifPresent(value -> locationFacts.add(
                        value ? "on a planet surface"
                                : "not on a planet surface"
                ));

        List<String> sentences = new ArrayList<>();
        sentences.add(
                "A ship landed on a planet surface"
                        + (landingFacts.isEmpty()
                        ? "."
                        : "; "
                                + LlmPresentableJournalEvent.joinFacts(
                                        landingFacts
                                )
                                + ".")
        );
        if (!locationFacts.isEmpty()) {
            sentences.add(
                    "The touchdown was recorded at "
                            + LlmPresentableJournalEvent.joinFacts(
                                    locationFacts
                            )
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
