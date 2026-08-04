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
 * {@code Location} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 4.12</a>
 */
public record Location(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Location";

    public Location {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();
        sentences.add(locationIdentity(event));
        sentences.add(
                "This is a state snapshot, not evidence of a new movement."
        );
        playerState(event).ifPresent(sentences::add);
        stationProfile(event).ifPresent(sentences::add);
        systemProfile(event).ifPresent(sentences::add);
        thargoidWar(event).ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
    }

    private static String locationIdentity(JsonNode event) {
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("StarSystem"))
                .ifPresent(value -> facts.add(
                        "star system "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.textual(event.get("Body"))
                .ifPresent(value -> facts.add(
                        "body "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("BodyID"))
                .ifPresent(value -> facts.add(
                        "body ID " + value
                ));
        LlmPresentableJournalEvent.displayText(event, "BodyType")
                .ifPresent(value -> facts.add(
                        "body type "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.decimal(event.get("DistFromStarLS"))
                .ifPresent(value -> facts.add(
                        "distance from the system's main star "
                                + value
                                + " light-seconds"
                ));

        return "At game startup or after resurrection at a station, the "
                + "journal recorded a current-location snapshot"
                + (facts.isEmpty()
                ? "."
                : " at "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
    }

    private static Optional<String> playerState(JsonNode event) {
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.booleanValue(event.get("Docked"))
                .ifPresent(value -> facts.add(
                        value ? "the player was docked"
                                : "the player was not docked"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Taxi"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> facts.add(
                        "the player was travelling in a taxi"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Multicrew"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> facts.add(
                        "the player was aboard another player's ship"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("InSRV"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> facts.add(
                        "the player was in an SRV"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("OnFoot"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> facts.add(
                        "the player was on foot"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Wanted"))
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> facts.add(
                        "the player was wanted in this system"
                ));
        LlmPresentableJournalEvent.decimal(event.get("Latitude"))
                .ifPresent(value -> facts.add(
                        "latitude " + value + " degrees"
                ));
        LlmPresentableJournalEvent.decimal(event.get("Longitude"))
                .ifPresent(value -> facts.add(
                        "longitude " + value + " degrees"
                ));
        return sentence("The snapshot reports ", facts);
    }

    private static Optional<String> stationProfile(JsonNode event) {
        List<String> facts = new ArrayList<>();
        addDisplayed(event, "StationName", "station", facts);
        addDisplayed(event, "StationType", "station type", facts);
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(value -> facts.add(
                        "market ID " + value
                ));
        JsonNode faction = event.get("StationFaction");
        if (faction != null && faction.isObject()) {
            LlmPresentableJournalEvent.displayText(faction, "Name")
                    .ifPresent(value -> facts.add(
                            "station faction "
                                    + LlmPresentableJournalEvent.quoted(value)
                    ));
        }
        addDisplayed(
                event,
                "StationGovernment",
                "station government",
                facts
        );
        addDisplayed(
                event,
                "StationAllegiance",
                "station allegiance",
                facts
        );
        addDisplayed(event, "StationEconomy", "station economy", facts);
        return sentence("The docked-location data reports ", facts);
    }

    private static Optional<String> systemProfile(JsonNode event) {
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
        addDisplayed(event, "PowerplayState", "Powerplay state", facts);
        addPowerNames(event.get("Powers"), facts);
        return sentence("The current system reports ", facts);
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
        LlmPresentableJournalEvent.booleanValue(
                        war.get("SuccessStateReached")
                )
                .ifPresent(value -> facts.add(
                        "success state reached " + value
                ));
        LlmPresentableJournalEvent.decimal(war.get("WarProgress"))
                .filter(Location::isZeroToOne)
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
            return Optional.of(
                    "The journal reports that this system is affected by "
                            + "the Thargoid war."
            );
        }
        return Optional.of(
                "The journal reports that this system is affected by the "
                        + "Thargoid war, with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + "."
        );
    }

    private static Optional<String> sentence(
            String prefix,
            List<String> facts
    ) {
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                prefix
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

    private static void addPowerNames(
            JsonNode powers,
            List<String> facts
    ) {
        if (powers == null || !powers.isArray()) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (JsonNode power : powers) {
            LlmPresentableJournalEvent.textual(power)
                    .map(LlmPresentableJournalEvent::quoted)
                    .ifPresent(names::add);
        }
        if (!names.isEmpty()) {
            facts.add("Powerplay powers " + String.join(", ", names));
        }
    }

    private static boolean isZeroToOne(String value) {
        BigDecimal number = new BigDecimal(value);
        return number.signum() >= 0
                && number.compareTo(BigDecimal.ONE) <= 0;
    }
}
