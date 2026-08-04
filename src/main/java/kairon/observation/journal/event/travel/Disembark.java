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
 * {@code Disembark} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 12.14</a>
 */
public record Disembark(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Disembark";

    public Disembark {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "The Commander stepped out of a ship or SRV.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sourceFacts = new ArrayList<>();
        Optional<Boolean> srvFlag = LlmPresentableJournalEvent.booleanValue(
                event.get("SRV")
        );
        Optional<Boolean> taxiFlag = LlmPresentableJournalEvent.booleanValue(
                event.get("Taxi")
        );
        Optional<Boolean> multicrewFlag =
                LlmPresentableJournalEvent.booleanValue(
                        event.get("Multicrew")
                );
        boolean srv = srvFlag.orElse(false);
        boolean taxi = taxiFlag.orElse(false);
        boolean multicrew = multicrewFlag.orElse(false);

        srvFlag.ifPresent(value -> sourceFacts.add(
                value ? "the player stepped out of an SRV"
                        : "the player stepped out of a ship"
        ));
        taxiFlag.ifPresent(value -> sourceFacts.add(
                value ? "the ship was a taxi"
                        : "the ship was not marked as a taxi"
        ));
        multicrewFlag.ifPresent(value -> sourceFacts.add(
                value ? "the vessel belonged to another player"
                        : "the vessel was not marked as another "
                                + "player's vessel"
        ));
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("ID"))
                .ifPresent(value -> sourceFacts.add(
                        idDescription(value, srv, taxi, multicrew)
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
        LlmPresentableJournalEvent.textual(event.get("StationName"))
                .ifPresent(value -> locationFacts.add(
                        "station "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.textual(event.get("StationType"))
                .ifPresent(value -> locationFacts.add(
                        "station type "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("OnStation"))
                .ifPresent(value -> locationFacts.add(
                        value ? "on a station" : "not on a station"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("OnPlanet"))
                .ifPresent(value -> locationFacts.add(
                        value ? "on a planet" : "not on a planet"
                ));

        List<String> sentences = new ArrayList<>();
        sentences.add(sourceFacts.isEmpty()
                ? "The player disembarked from a ship or SRV."
                : "The player disembarked: "
                        + LlmPresentableJournalEvent.joinFacts(sourceFacts)
                        + ".");
        if (!locationFacts.isEmpty()) {
            sentences.add(
                    "The recorded disembarkation location is "
                            + LlmPresentableJournalEvent.joinFacts(
                                    locationFacts
                            )
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }

    private static String idDescription(
            long value,
            boolean srv,
            boolean taxi,
            boolean multicrew
    ) {
        if (srv || taxi || multicrew) {
            return "the event reported ID " + value;
        }
        return "the player's own ship ID was " + value;
    }
}
