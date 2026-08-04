package kairon.observation.journal.event.carrier;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code CarrierJump} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, sections 11.1 and 4.8</a>
 */
public record CarrierJump(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "CarrierJump";

    public CarrierJump {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A fleet carrier jumped while the Commander was docked at it.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();
        sentences.add(jumpIdentity(event));
        dockedState(event).ifPresent(sentences::add);
        destinationBody(event).ifPresent(sentences::add);
        systemProfile(event).ifPresent(sentences::add);
        thargoidWar(event).ifPresent(sentences::add);
        sentences.add(
                "This CarrierJump event does not report the distance jumped "
                        + "or the fuel used."
        );
        return new LlmEventPresentation(sentences);
    }

    private static String jumpIdentity(JsonNode event) {
        StringBuilder sentence = new StringBuilder(
                "The journal recorded a fleet-carrier hyperspace jump"
        );
        LlmPresentableJournalEvent.textual(event.get("StationName"))
                .ifPresent(name -> sentence
                        .append(" by carrier ")
                        .append(LlmPresentableJournalEvent.quoted(name)));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(marketId -> sentence
                        .append(" (carrier market ID ")
                        .append(marketId)
                        .append(')'));
        LlmPresentableJournalEvent.textual(event.get("StarSystem"))
                .ifPresent(system -> sentence
                        .append(" to star system ")
                        .append(LlmPresentableJournalEvent.quoted(system)));
        sentence.append('.');
        return sentence.toString();
    }

    private static Optional<String> dockedState(JsonNode event) {
        return LlmPresentableJournalEvent.booleanValue(event.get("Docked"))
                .map(docked -> docked
                        ? "The player was docked at this fleet carrier "
                                + "during the jump."
                        : "The journal explicitly reports that the player "
                                + "was not docked at this fleet carrier "
                                + "during the event.");
    }

    private static Optional<String> destinationBody(JsonNode event) {
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("Body"))
                .ifPresent(body -> facts.add(
                        "body " + LlmPresentableJournalEvent.quoted(body)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("BodyID"))
                .ifPresent(bodyId -> facts.add("body ID " + bodyId));
        LlmPresentableJournalEvent.textual(event.get("BodyType"))
                .ifPresent(bodyType -> facts.add(
                        "body type "
                                + LlmPresentableJournalEvent.quoted(bodyType)
                ));
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The destination reference is "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + "."
        );
    }

    private static Optional<String> systemProfile(JsonNode event) {
        List<String> facts = new ArrayList<>();
        addDisplayedFact(event, "SystemAllegiance", "allegiance", facts);
        addDisplayedFact(event, "SystemEconomy", "primary economy", facts);
        addDisplayedFact(
                event,
                "SystemSecondEconomy",
                "secondary economy",
                facts
        );
        addDisplayedFact(event, "SystemGovernment", "government", facts);
        addDisplayedFact(event, "SystemSecurity", "security", facts);
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("Population"))
                .ifPresent(population -> facts.add(
                        "population "
                                + LlmPresentableJournalEvent
                                        .formattedInteger(population)
                ));
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The destination system reports "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + "."
        );
    }

    private static Optional<String> thargoidWar(JsonNode event) {
        JsonNode war = event.get("ThargoidWar");
        if (war == null || !war.isObject()) {
            return Optional.empty();
        }
        List<String> facts = new ArrayList<>();
        addDisplayedFact(war, "CurrentState", "current state", facts);
        addDisplayedFact(
                war,
                "NextStateSuccess",
                "next state after success",
                facts
        );
        addDisplayedFact(
                war,
                "NextStateFailure",
                "next state after failure",
                facts
        );
        LlmPresentableJournalEvent
                .booleanValue(war.get("SuccessStateReached"))
                .ifPresent(reached -> facts.add(
                        reached
                                ? "the success state has been reached"
                                : "the success state has not been reached"
                ));
        LlmPresentableJournalEvent.decimal(war.get("WarProgress"))
                .filter(CarrierJump::isZeroToOne)
                .ifPresent(progress -> facts.add(
                        "war progress " + progress + " on a 0-to-1 scale"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(war.get("RemainingPorts"))
                .ifPresent(ports -> facts.add(
                        "remaining ports "
                                + LlmPresentableJournalEvent
                                        .formattedInteger(ports)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(war.get("EstimatedRemainingTime"))
                .ifPresent(estimate -> facts.add(
                        "estimated remaining time numeric source value "
                                + estimate
                                + " (unit not defined by the cited Journal "
                                + "Manual)"
                ));
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The journal reports that the destination system is affected "
                        + "by the Thargoid war, with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + "."
        );
    }

    private static void addDisplayedFact(
            JsonNode event,
            String fieldName,
            String label,
            List<String> facts
    ) {
        LlmPresentableJournalEvent.displayText(event, fieldName)
                .ifPresent(value -> facts.add(
                        label + " " + LlmPresentableJournalEvent.quoted(value)
                ));
    }

    private static boolean isZeroToOne(String value) {
        BigDecimal number = new BigDecimal(value);
        return number.signum() >= 0
                && number.compareTo(BigDecimal.ONE) <= 0;
    }
}
