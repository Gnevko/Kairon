package kairon.observation.journal.event.travel;

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
 * {@code FSDJump} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 4.8</a>
 */
public record FSDJump(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "FSDJump";

    public FSDJump {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();
        sentences.add(jumpIdentity(event));
        jumpOperation(event).ifPresent(sentences::add);
        destinationProfile(event).ifPresent(sentences::add);
        thargoidWar(event).ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
    }

    private static String jumpIdentity(JsonNode event) {
        String destination = LlmPresentableJournalEvent
                .textual(event.get("StarSystem"))
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified star system");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("Body"))
                .ifPresent(value -> facts.add(
                        "arrival body "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.textual(event.get("BodyType"))
                .ifPresent(value -> facts.add(
                        "arrival body type "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.decimal(event.get("JumpDist"))
                .ifPresent(value -> facts.add(
                        "jump distance " + value + " light-years"
                ));
        return "The player completed a hyperspace jump to star system "
                + destination
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
    }

    private static Optional<String> jumpOperation(JsonNode event) {
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.decimal(event.get("FuelUsed"))
                .ifPresent(value -> facts.add(
                        "fuel used " + value + " tonnes"
                ));
        LlmPresentableJournalEvent.decimal(event.get("FuelLevel"))
                .ifPresent(value -> facts.add(
                        "fuel remaining " + value + " tonnes"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("BoostUsed"))
                .ifPresent(value -> facts.add(
                        value
                                ? "an FSD boost was used"
                                : "no FSD boost was used"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Taxi"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> facts.add(
                        "the player travelled in a taxi"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Multicrew"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> facts.add(
                        "the player travelled aboard another player's vessel"
                ));
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "For this jump, the journal reports "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + "."
        );
    }

    private static Optional<String> destinationProfile(JsonNode event) {
        List<String> facts = new ArrayList<>();
        addDisplayed(event, "SystemAllegiance", "allegiance", facts);
        addDisplayed(event, "SystemEconomy", "primary economy", facts);
        addDisplayed(
                event,
                "SystemSecondEconomy",
                "secondary economy",
                facts
        );
        addDisplayed(event, "SystemGovernment", "government", facts);
        addDisplayed(event, "SystemSecurity", "security", facts);
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("Population"))
                .ifPresent(value -> facts.add(
                        "population "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
                ));
        JsonNode faction = event.get("SystemFaction");
        if (faction != null && faction.isObject()) {
            LlmPresentableJournalEvent.displayText(faction, "Name")
                    .ifPresent(value -> facts.add(
                            "controlling faction "
                                    + LlmPresentableJournalEvent.quoted(value)
                    ));
        }
        LlmPresentableJournalEvent.booleanValue(event.get("Wanted"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> facts.add(
                        "the player was wanted in the destination system"
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
        addDisplayed(war, "CurrentState", "current state", facts);
        addDisplayed(
                war,
                "NextStateSuccess",
                "next state after success",
                facts
        );
        addDisplayed(
                war,
                "NextStateFailure",
                "next state after failure",
                facts
        );
        LlmPresentableJournalEvent.decimal(war.get("WarProgress"))
                .filter(FSDJump::isZeroToOne)
                .ifPresent(value -> facts.add(
                        "war progress " + value + " on a 0-to-1 scale"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(war.get("RemainingPorts"))
                .ifPresent(value -> facts.add(
                        "remaining ports "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
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

    private static void addDisplayed(
            JsonNode event,
            String field,
            String label,
            List<String> facts
    ) {
        LlmPresentableJournalEvent.displayText(event, field)
                .ifPresent(value -> facts.add(
                        label
                                + " "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
    }

    private static boolean isZeroToOne(String value) {
        BigDecimal number = new BigDecimal(value);
        return number.signum() >= 0
                && number.compareTo(BigDecimal.ONE) <= 0;
    }
}
