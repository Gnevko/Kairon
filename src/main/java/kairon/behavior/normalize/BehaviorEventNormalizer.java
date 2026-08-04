package kairon.behavior.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.event.combat.Interdicted;
import kairon.observation.journal.event.combat.UnderAttack;
import kairon.observation.journal.event.exploration.FSSAllBodiesFound;
import kairon.observation.journal.event.exploration.FSSBodySignals;
import kairon.observation.journal.event.exploration.FSSDiscoveryScan;
import kairon.observation.journal.event.exploration.SAAScanComplete;
import kairon.observation.journal.event.exploration.SAASignalsFound;
import kairon.observation.journal.event.exploration.Scan;
import kairon.observation.journal.event.exploration.ScanOrganic;
import kairon.observation.journal.event.inventory.MaterialCollected;
import kairon.observation.journal.event.mission.MissionAbandoned;
import kairon.observation.journal.event.mission.MissionAccepted;
import kairon.observation.journal.event.mission.MissionCompleted;
import kairon.observation.journal.event.mission.MissionFailed;
import kairon.observation.journal.event.ship.DockSRV;
import kairon.observation.journal.event.ship.LaunchDrone;
import kairon.observation.journal.event.ship.LaunchFighter;
import kairon.observation.journal.event.ship.LaunchSRV;
import kairon.observation.journal.event.trade.MarketBuy;
import kairon.observation.journal.event.trade.MarketSell;
import kairon.observation.journal.event.trade.RedeemVoucher;
import kairon.observation.journal.event.travel.ApproachBody;
import kairon.observation.journal.event.travel.Disembark;
import kairon.observation.journal.event.travel.Docked;
import kairon.observation.journal.event.travel.DockingGranted;
import kairon.observation.journal.event.travel.DockingRequested;
import kairon.observation.journal.event.travel.Embark;
import kairon.observation.journal.event.travel.FSDJump;
import kairon.observation.journal.event.travel.FSDTarget;
import kairon.observation.journal.event.travel.FuelScoop;
import kairon.observation.journal.event.travel.LeaveBody;
import kairon.observation.journal.event.travel.Liftoff;
import kairon.observation.journal.event.travel.Location;
import kairon.observation.journal.event.travel.StartJump;
import kairon.observation.journal.event.travel.SupercruiseEntry;
import kairon.observation.journal.event.travel.SupercruiseExit;
import kairon.observation.journal.event.travel.Touchdown;
import kairon.observation.journal.event.travel.Undocked;
import kairon.semantics.BodySurveyFacts;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts explicitly selected typed journal events to compact graph events.
 */
public final class BehaviorEventNormalizer {

    private static final Map<
            Class<? extends JournalEventObservation>,
            DirectRule
            > DIRECT_RULES = directRules();

    /**
     * Every attribute a scan's admission policy compares, and nothing that only
     * the raw record needs: the occurrence is what a later scan of the same body
     * is compared against, and the arrival-star milestone is compared against
     * the same fields.
     */
    private static final List<String> SCAN_ATTRIBUTES = List.of(
            "SystemAddress",
            "BodyID",
            "BodyName",
            "StarSystem",
            "ScanType",
            "PlanetClass",
            "StarType",
            "Landable",
            "TerraformState",
            "Atmosphere",
            "Volcanism",
            "WasDiscovered",
            "WasMapped",
            "WasFootfalled",
            "DistanceFromArrivalLS"
    );

    public NormalizedBehaviorEvent normalize(
            PublishedObservation<? extends JournalEventObservation> observation
    ) {
        Objects.requireNonNull(observation, "observation");
        Instant timestamp = observation.sourceTime()
                .or(() -> observation.payload()
                        .raw()
                        .optionalJournalTimestamp())
                .orElse(observation.observedAt());
        return normalize(observation.payload(), timestamp);
    }

    /**
     * Explicit timestamp overload keeps tests, replay, and synthetic callers
     * independent of the wall clock.
     */
    public NormalizedBehaviorEvent normalize(
            JournalEventObservation event,
            Instant timestamp
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(timestamp, "timestamp");
        if (event instanceof FSDJump) {
            throw new IllegalArgumentException(
                    "FSDJump must be normalized only as a SYSTEM_ENTRY boundary"
            );
        }

        JsonNode raw = event.raw().parsedJsonObject();
        String originalEventName = originalEventName(event);
        if (event instanceof ScanOrganic) {
            return normalizeScanOrganic(raw, timestamp, originalEventName);
        }
        if (event instanceof Scan) {
            return normalizeScan(raw, timestamp, originalEventName);
        }
        if (event instanceof StartJump) {
            return normalizeStartJump(raw, timestamp, originalEventName);
        }
        if (event instanceof LaunchDrone) {
            return normalizeLaunchDrone(raw, timestamp, originalEventName);
        }

        DirectRule rule = DIRECT_RULES.get(event.getClass());
        if (rule != null) {
            return event(
                    rule.eventType(),
                    timestamp,
                    raw,
                    originalEventName,
                    rule.attributeNames()
            );
        }
        return event(
                NormalizedEventType.unknown(originalEventName),
                timestamp,
                raw,
                originalEventName,
                commonAttributeNames()
        );
    }

    /**
     * The only normalizer entry point that accepts FSDJump or a restoring
     * Location as the root occurrence of an episode.
     */
    public NormalizedBehaviorEvent normalizeSystemEntry(
            PublishedObservation<? extends JournalEventObservation> observation
    ) {
        Objects.requireNonNull(observation, "observation");
        JournalEventObservation payload = observation.payload();
        Instant timestamp = observation.sourceTime()
                .or(() -> payload.raw().optionalJournalTimestamp())
                .orElse(observation.observedAt());
        return normalizeSystemEntry(payload, timestamp);
    }

    public NormalizedBehaviorEvent normalizeSystemEntry(
            JournalEventObservation payload,
            Instant timestamp
    ) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(timestamp, "timestamp");
        if (!(payload instanceof FSDJump) && !(payload instanceof Location)) {
            throw new IllegalArgumentException(
                    "SYSTEM_ENTRY requires FSDJump or Location"
            );
        }
        JsonNode raw = payload.raw().parsedJsonObject();
        return event(
                NormalizedEventType.SYSTEM_ENTRY,
                timestamp,
                raw,
                originalEventName(payload),
                List.of(
                        "StarSystem",
                        "SystemAddress",
                        "Body",
                        "BodyID",
                        "BodyType"
                )
        );
    }

    private static NormalizedBehaviorEvent normalizeScanOrganic(
            JsonNode raw,
            Instant timestamp,
            String originalEventName
    ) {
        NormalizedEventType eventType = switch (text(raw, "ScanType")) {
            case "Log" -> NormalizedEventType.SCAN_ORGANIC_LOG;
            case "Sample" -> NormalizedEventType.SCAN_ORGANIC_SAMPLE;
            case "Analyse" -> NormalizedEventType.SCAN_ORGANIC_ANALYSE;
            default -> NormalizedEventType.unknown(originalEventName);
        };
        return event(
                eventType,
                timestamp,
                raw,
                originalEventName,
                List.of(
                        "SystemAddress",
                        "Body",
                        "ScanType",
                        "Genus",
                        "Genus_Localised",
                        "Species",
                        "Species_Localised",
                        "Variant",
                        "Variant_Localised",
                        "WasLogged"
                )
        );
    }

    /**
     * A scan is two structural things, told apart by what the record says.
     *
     * <p>A detailed reading establishes a body, and that is
     * {@link NormalizedEventType#BODY_SCANNED}. A shallower reading of a star
     * that reports no prior discovery establishes something else entirely —
     * that nobody had been here — and it is the only record in the journal that
     * carries the arrival star's class and discovery flag at all. Giving it the
     * scan's own type would file a milestone as a scan result; leaving it
     * unrecorded is what lost the fact.</p>
     *
     * <p>Only the shape is decided here. Whether the star is the one this visit
     * arrived at, and whether the visit has already been told, are episode
     * questions and belong to {@code BodySurveySelectionPolicy}.</p>
     */
    private static NormalizedBehaviorEvent normalizeScan(
            JsonNode raw,
            Instant timestamp,
            String originalEventName
    ) {
        return event(
                BodySurveyFacts.undiscoveredStarReading(raw)
                        ? NormalizedEventType.SYSTEM_UNDISCOVERED_CONFIRMED
                        : NormalizedEventType.BODY_SCANNED,
                timestamp,
                raw,
                originalEventName,
                SCAN_ATTRIBUTES
        );
    }

    private static NormalizedBehaviorEvent normalizeStartJump(
            JsonNode raw,
            Instant timestamp,
            String originalEventName
    ) {
        NormalizedEventType eventType = switch (text(raw, "JumpType")) {
            case "Hyperspace" -> NormalizedEventType.HYPERSPACE_JUMP_STARTED;
            case "Supercruise" ->
                    NormalizedEventType.SUPERCRUISE_JUMP_STARTED;
            default -> NormalizedEventType.unknown(originalEventName);
        };
        return event(
                eventType,
                timestamp,
                raw,
                originalEventName,
                List.of(
                        "JumpType",
                        "StarSystem",
                        "SystemAddress",
                        "StarClass"
                )
        );
    }

    private static NormalizedBehaviorEvent normalizeLaunchDrone(
            JsonNode raw,
            Instant timestamp,
            String originalEventName
    ) {
        NormalizedEventType eventType = switch (text(raw, "Type")) {
            case "Hatchbreaker" ->
                    NormalizedEventType.HATCH_BREAKER_LIMPET_LAUNCHED;
            case "FuelTransfer" ->
                    NormalizedEventType.FUEL_TRANSFER_LIMPET_LAUNCHED;
            case "Collection" ->
                    NormalizedEventType.COLLECTION_LIMPET_LAUNCHED;
            case "Prospector" ->
                    NormalizedEventType.PROSPECTOR_LIMPET_LAUNCHED;
            case "Repair" ->
                    NormalizedEventType.REPAIR_LIMPET_LAUNCHED;
            case "Research" ->
                    NormalizedEventType.RESEARCH_LIMPET_LAUNCHED;
            case "Decontamination" ->
                    NormalizedEventType.DECONTAMINATION_LIMPET_LAUNCHED;
            case "Recon" ->
                    NormalizedEventType.RECON_LIMPET_LAUNCHED;
            default -> NormalizedEventType.LIMPET_LAUNCHED;
        };
        return event(
                eventType,
                timestamp,
                raw,
                originalEventName,
                List.of("Type")
        );
    }

    private static NormalizedBehaviorEvent event(
            NormalizedEventType eventType,
            Instant timestamp,
            JsonNode raw,
            String originalEventName,
            List<String> attributeNames
    ) {
        return new NormalizedBehaviorEvent(
                eventType,
                timestamp,
                selectAttributes(raw, attributeNames),
                originalEventName
        );
    }

    private static Map<String, JsonNode> selectAttributes(
            JsonNode raw,
            List<String> names
    ) {
        Map<String, JsonNode> attributes = new LinkedHashMap<>();
        names.stream().sorted().forEach(name -> {
            JsonNode value = raw.get(name);
            if (value != null && !value.isNull()) {
                attributes.put(name, value.deepCopy());
            }
        });
        return attributes;
    }

    private static String originalEventName(JournalEventObservation event) {
        return event.raw()
                .optionalEventType()
                .filter(value -> !value.isBlank())
                .orElseGet(() -> event.getClass().getSimpleName());
    }

    private static String text(JsonNode raw, String name) {
        JsonNode value = raw.get(name);
        return value != null && value.isTextual()
                ? value.textValue()
                : "";
    }

    private static List<String> commonAttributeNames() {
        return List.of(
                "SystemAddress",
                "StarSystem",
                "BodyID",
                "Body",
                "BodyName",
                "MarketID",
                "MissionID",
                "ShipID"
        );
    }

    private static Map<Class<? extends JournalEventObservation>, DirectRule>
            directRules() {
        Map<Class<? extends JournalEventObservation>, DirectRule> rules =
                new LinkedHashMap<>();
        register(
                rules,
                FSSDiscoveryScan.class,
                NormalizedEventType.FSS_DISCOVERY_SCAN,
                "SystemAddress",
                "SystemName",
                "Progress",
                "BodyCount",
                "NonBodyCount"
        );
        register(
                rules,
                FSSAllBodiesFound.class,
                NormalizedEventType.FSS_ALL_BODIES_FOUND,
                "SystemAddress",
                "SystemName",
                "Count"
        );
        // The completed survey itself. ProbesUsed and EfficiencyTarget are
        // kept because they are what distinguishes an efficient mapping from a
        // wasteful one, and the pair is already how the model-facing event
        // reports it.
        register(
                rules,
                SAAScanComplete.class,
                NormalizedEventType.SAA_SCAN_COMPLETE,
                "SystemAddress",
                "BodyID",
                "BodyName",
                "ProbesUsed",
                "EfficiencyTarget"
        );
        register(
                rules,
                SAASignalsFound.class,
                NormalizedEventType.SAA_SIGNALS_FOUND,
                "SystemAddress",
                "BodyID",
                "BodyName",
                "Signals",
                "Genuses"
        );
        // The same signal set from the system scanner rather than the surface
        // one. Kept as its own structural type because the two are different
        // ways of finding out; the model is told the finding, not the scanner.
        register(
                rules,
                FSSBodySignals.class,
                NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                "SystemAddress",
                "BodyID",
                "BodyName",
                "Signals"
        );
        register(
                rules,
                ApproachBody.class,
                NormalizedEventType.APPROACH_BODY,
                "StarSystem",
                "SystemAddress",
                "Body",
                "BodyID"
        );
        register(
                rules,
                SupercruiseEntry.class,
                NormalizedEventType.SUPERCRUISE_ENTRY,
                "StarSystem",
                "SystemAddress"
        );
        register(
                rules,
                SupercruiseExit.class,
                NormalizedEventType.SUPERCRUISE_EXIT,
                "StarSystem",
                "SystemAddress",
                "Body",
                "BodyID",
                "BodyType"
        );
        register(
                rules,
                FSDTarget.class,
                NormalizedEventType.FSD_TARGET_SELECTED,
                "Name",
                "SystemAddress",
                "RemainingJumpsInRoute",
                "StarClass"
        );
        register(
                rules,
                LaunchFighter.class,
                NormalizedEventType.AUXILIARY_VEHICLE_LAUNCHED,
                "Loadout",
                "ID",
                "PlayerControlled"
        );
        register(
                rules,
                LaunchSRV.class,
                NormalizedEventType.AUXILIARY_VEHICLE_LAUNCHED,
                "Loadout",
                "ID",
                "PlayerControlled",
                "SRVType",
                "SRVType_Localised"
        );
        register(
                rules,
                Touchdown.class,
                NormalizedEventType.TOUCHDOWN,
                "StarSystem",
                "SystemAddress",
                "Body",
                "BodyID",
                "OnStation",
                "OnPlanet",
                "PlayerControlled",
                "Latitude",
                "Longitude"
        );
        register(
                rules,
                Disembark.class,
                NormalizedEventType.DISEMBARK,
                "StarSystem",
                "SystemAddress",
                "Body",
                "BodyID",
                "OnStation",
                "OnPlanet",
                "SRV",
                "ID"
        );
        register(
                rules,
                Embark.class,
                NormalizedEventType.EMBARK,
                "StarSystem",
                "SystemAddress",
                "Body",
                "BodyID",
                "OnStation",
                "OnPlanet",
                "SRV",
                "ID"
        );
        register(
                rules,
                Liftoff.class,
                NormalizedEventType.LIFTOFF,
                "StarSystem",
                "SystemAddress",
                "Body",
                "BodyID",
                "OnStation",
                "OnPlanet",
                "PlayerControlled",
                "Latitude",
                "Longitude"
        );
        register(
                rules,
                LeaveBody.class,
                NormalizedEventType.LEAVE_BODY,
                "StarSystem",
                "SystemAddress",
                "Body",
                "BodyID"
        );
        register(
                rules,
                DockSRV.class,
                NormalizedEventType.AUXILIARY_VEHICLE_DOCKED,
                "ID",
                "SRVType",
                "SRVType_Localised"
        );
        register(
                rules,
                Docked.class,
                NormalizedEventType.DOCKED,
                "StarSystem",
                "SystemAddress",
                "StationName",
                "StationType",
                "MarketID"
        );
        register(
                rules,
                DockingRequested.class,
                NormalizedEventType.DOCKING_REQUESTED,
                "StationName",
                "StationType",
                "MarketID",
                "LandingPads"
        );
        register(
                rules,
                DockingGranted.class,
                NormalizedEventType.DOCKING_GRANTED,
                "StationName",
                "StationType",
                "MarketID",
                "LandingPad"
        );
        register(
                rules,
                Undocked.class,
                NormalizedEventType.UNDOCKED,
                "StationName",
                "StationType",
                "MarketID"
        );
        register(
                rules,
                Interdicted.class,
                NormalizedEventType.INTERDICTED,
                "Submitted",
                "Interdictor",
                "IsPlayer",
                "IsThargoid",
                "CombatRank",
                "Faction",
                "Power"
        );
        register(
                rules,
                UnderAttack.class,
                NormalizedEventType.UNDER_ATTACK,
                "Target"
        );
        register(
                rules,
                MaterialCollected.class,
                NormalizedEventType.MATERIAL_COLLECTED,
                "Category",
                "Name",
                "Name_Localised",
                "Count"
        );
        register(
                rules,
                FuelScoop.class,
                NormalizedEventType.FUEL_SCOOPING,
                "Scooped",
                "Total"
        );
        register(
                rules,
                MarketBuy.class,
                NormalizedEventType.MARKET_BUY,
                "MarketID",
                "Type",
                "Type_Localised",
                "Count",
                "BuyPrice",
                "TotalCost"
        );
        register(
                rules,
                MarketSell.class,
                NormalizedEventType.MARKET_SELL,
                "MarketID",
                "Type",
                "Type_Localised",
                "Count",
                "SellPrice",
                "TotalSale"
        );
        register(
                rules,
                RedeemVoucher.class,
                NormalizedEventType.REDEEM_VOUCHER,
                "Type",
                "Amount",
                "Faction",
                "BrokerPercentage"
        );
        register(
                rules,
                MissionAccepted.class,
                NormalizedEventType.MISSION_ACCEPTED,
                missionAttributeNames()
        );
        register(
                rules,
                MissionCompleted.class,
                NormalizedEventType.MISSION_COMPLETED,
                missionAttributeNames()
        );
        register(
                rules,
                MissionFailed.class,
                NormalizedEventType.MISSION_FAILED,
                missionAttributeNames()
        );
        register(
                rules,
                MissionAbandoned.class,
                NormalizedEventType.MISSION_ABANDONED,
                missionAttributeNames()
        );
        return Map.copyOf(rules);
    }

    private static String[] missionAttributeNames() {
        return new String[]{
                "MissionID",
                "Name",
                "LocalisedName",
                "DestinationSystem",
                "DestinationStation",
                "Reward"
        };
    }

    private static void register(
            Map<Class<? extends JournalEventObservation>, DirectRule> rules,
            Class<? extends JournalEventObservation> payloadType,
            NormalizedEventType eventType,
            String... attributeNames
    ) {
        DirectRule previous = rules.putIfAbsent(
                payloadType,
                new DirectRule(eventType, List.of(attributeNames))
        );
        if (previous != null) {
            throw new ExceptionInInitializerError(
                    "Duplicate behavior normalizer for "
                            + payloadType.getName()
            );
        }
    }

    private record DirectRule(
            NormalizedEventType eventType,
            List<String> attributeNames
    ) {
        private DirectRule {
            Objects.requireNonNull(eventType, "eventType");
            attributeNames = List.copyOf(attributeNames);
        }
    }
}
