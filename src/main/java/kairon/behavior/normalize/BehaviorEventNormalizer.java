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

import java.time.Instant;
import java.util.ArrayList;
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

    private static final Map<
            NormalizedEventType,
            List<Class<? extends JournalEventObservation>>
            > JOURNAL_TYPES_BY_NORMALIZED_TYPE = journalTypesByNormalizedType();

    /**
     * Which journal classes a normalized type was projected from.
     *
     * <p>The inverse of the rule table, and read by the GUI: a graph node is a
     * normalized type, and whether observations of it ever reach the model is a
     * question about the journal classes behind it. More than one class can
     * share a type — either scanner reports a set of signals — so the answer is
     * a list. Empty means the type was not projected from a journal record at
     * all, which for now means it came from {@code Status.json}: those six
     * never open a model turn and no journal contains them.</p>
     */
    public static List<Class<? extends JournalEventObservation>> journalTypesOf(
            NormalizedEventType eventType
    ) {
        Objects.requireNonNull(eventType, "eventType");
        return JOURNAL_TYPES_BY_NORMALIZED_TYPE.getOrDefault(
                eventType,
                List.of()
        );
    }

    private static Map<
            NormalizedEventType,
            List<Class<? extends JournalEventObservation>>
            > journalTypesByNormalizedType() {
        Map<NormalizedEventType, List<Class<? extends JournalEventObservation>>>
                inverse = new LinkedHashMap<>();
        DIRECT_RULES.forEach((journalType, rule) -> {
            // A rule may carry no constant type: the variant decides it, and
            // an unrecognised discriminator mints an unknown one at read time.
            // Those have no fixed node to answer for.
            if (rule.eventType() == null) {
                return;
            }
            inverse.computeIfAbsent(
                    rule.eventType(),
                    ignored -> new ArrayList<>()
            ).add(journalType);
        });
        // SYSTEM_ENTRY is minted by normalizeSystemEntry rather than by a
        // rule, and its two records are the ones that method accepts. Without
        // this the root of every episode would answer "from no journal record".
        inverse.put(
                NormalizedEventType.SYSTEM_ENTRY,
                new ArrayList<>(List.of(FSDJump.class, Location.class))
        );
        Map<NormalizedEventType, List<Class<? extends JournalEventObservation>>>
                copy = new LinkedHashMap<>();
        inverse.forEach((eventType, journalTypes) ->
                copy.put(eventType, List.copyOf(journalTypes)));
        return Map.copyOf(copy);
    }

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
        DirectRule rule = DIRECT_RULES.get(event.getClass());
        if (rule != null) {
            return event(
                    // A variant whose discriminator this build does not
                    // recognise keeps its wire event's attribute list — it is
                    // the same record — but never borrows a researched type.
                    rule.eventType() == null
                            ? NormalizedEventType.unknown(originalEventName)
                            : rule.eventType(),
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

    /**
     * What every step of a sampling sequence records; one wire event.
     *
     * <p>A method rather than a constant: {@code DIRECT_RULES} is built by a
     * static field declared above every attribute constant in this class, so a
     * constant read while that map is being built would still be null.</p>
     */
    private static String[] scanOrganicAttributes() {
        return new String[]{
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
        };
    }

    /**
     * Every attribute a scan's admission policy compares, and nothing that only
     * the raw record needs: the occurrence is what a later scan of the same body
     * is compared against, and the arrival-star milestone is compared against
     * the same fields. Both readings of the record keep the same list — they
     * differ in what they assert, not in which fields they were read from.
     *
     * <p>A method rather than a constant, for the reason
     * {@link #scanOrganicAttributes()} gives.</p>
     */
    private static String[] scanAttributes() {
        return new String[]{
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
        };
    }

    /** What either charge records; one wire event. */
    private static String[] startJumpAttributes() {
        return new String[]{
                "JumpType",
                "StarSystem",
                "SystemAddress",
                "StarClass"
        };
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
        // The two things a scan record reports. A reading establishes what a
        // body is; a shallower reading of a star that reports no prior
        // discovery establishes that nobody had been here, and is the only
        // record in the journal carrying the arrival star's class and
        // discovery flag at all. Giving the second one the scan's own type
        // would file a milestone as a scan result; leaving it unrecorded is
        // what lost the fact. Whether that star is the one this visit arrived
        // at, and whether the visit has already been told, are episode
        // questions and belong to BodySurveySelectionPolicy.
        register(
                rules,
                Scan.BodyReading.class,
                NormalizedEventType.BODY_SCANNED,
                scanAttributes()
        );
        register(
                rules,
                Scan.UndiscoveredStar.class,
                NormalizedEventType.SYSTEM_UNDISCOVERED_CONFIRMED,
                scanAttributes()
        );
        // The four steps of an organic sampling sequence. Ordinary rules now:
        // the parser has already told them apart, so there is nothing left
        // here to switch on.
        register(
                rules,
                ScanOrganic.Logged.class,
                NormalizedEventType.SCAN_ORGANIC_LOG,
                scanOrganicAttributes()
        );
        register(
                rules,
                ScanOrganic.Sampled.class,
                NormalizedEventType.SCAN_ORGANIC_SAMPLE,
                scanOrganicAttributes()
        );
        register(
                rules,
                ScanOrganic.Analysed.class,
                NormalizedEventType.SCAN_ORGANIC_ANALYSE,
                scanOrganicAttributes()
        );
        registerUnrecognised(
                rules,
                ScanOrganic.Unrecognised.class,
                scanOrganicAttributes()
        );
        // Charging for another system, or for supercruise.
        register(
                rules,
                StartJump.Hyperspace.class,
                NormalizedEventType.HYPERSPACE_JUMP_STARTED,
                startJumpAttributes()
        );
        register(
                rules,
                StartJump.Supercruise.class,
                NormalizedEventType.SUPERCRUISE_JUMP_STARTED,
                startJumpAttributes()
        );
        registerUnrecognised(
                rules,
                StartJump.Unrecognised.class,
                startJumpAttributes()
        );
        // The eight researched limpet kinds, plus a launch the journal did not
        // name canonically. Ordinary rules now; the parser told them apart.
        register(rules, LaunchDrone.HatchBreaker.class,
                NormalizedEventType.HATCH_BREAKER_LIMPET_LAUNCHED, "Type");
        register(rules, LaunchDrone.FuelTransfer.class,
                NormalizedEventType.FUEL_TRANSFER_LIMPET_LAUNCHED, "Type");
        register(rules, LaunchDrone.Collection.class,
                NormalizedEventType.COLLECTION_LIMPET_LAUNCHED, "Type");
        register(rules, LaunchDrone.Prospector.class,
                NormalizedEventType.PROSPECTOR_LIMPET_LAUNCHED, "Type");
        register(rules, LaunchDrone.Repair.class,
                NormalizedEventType.REPAIR_LIMPET_LAUNCHED, "Type");
        register(rules, LaunchDrone.Research.class,
                NormalizedEventType.RESEARCH_LIMPET_LAUNCHED, "Type");
        register(rules, LaunchDrone.Decontamination.class,
                NormalizedEventType.DECONTAMINATION_LIMPET_LAUNCHED, "Type");
        register(rules, LaunchDrone.Recon.class,
                NormalizedEventType.RECON_LIMPET_LAUNCHED, "Type");
        register(rules, LaunchDrone.Unspecified.class,
                NormalizedEventType.LIMPET_LAUNCHED, "Type");
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

    /**
     * The unrecognised variant of a split record.
     *
     * <p>Same wire event, same attributes, no researched type. Registering it
     * keeps the attribute list with the record it belongs to instead of
     * dropping the occurrence onto the generic fallback.</p>
     */
    private static void registerUnrecognised(
            Map<Class<? extends JournalEventObservation>, DirectRule> rules,
            Class<? extends JournalEventObservation> payloadType,
            String... attributeNames
    ) {
        register(rules, payloadType, null, attributeNames);
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

    /**
     * @param eventType the structural type, or {@code null} for a variant whose
     *                  discriminator this build does not recognise. Null is the
     *                  only way to say "this record's attributes, but no
     *                  researched type", and it is reachable only through
     *                  {@link #registerUnrecognised}.
     */
    private record DirectRule(
            NormalizedEventType eventType,
            List<String> attributeNames
    ) {
        private DirectRule {
            attributeNames = List.copyOf(attributeNames);
        }
    }
}
