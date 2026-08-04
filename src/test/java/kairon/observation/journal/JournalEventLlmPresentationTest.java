package kairon.observation.journal;

import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;
import kairon.observation.journal.event.carrier.CarrierBuy;
import kairon.observation.journal.event.carrier.CarrierCancelDecommission;
import kairon.observation.journal.event.carrier.CarrierDecommission;
import kairon.observation.journal.event.carrier.CarrierJump;
import kairon.observation.journal.event.carrier.CarrierJumpCancelled;
import kairon.observation.journal.event.carrier.CarrierJumpRequest;
import kairon.observation.journal.event.carrier.CarrierNameChange;
import kairon.observation.journal.event.colonisation.ColonisationBeaconDeployed;
import kairon.observation.journal.event.colonisation.ColonisationConstructionDepot;
import kairon.observation.journal.event.colonisation.ColonisationContribution;
import kairon.observation.journal.event.colonisation.ColonisationSystemClaim;
import kairon.observation.journal.event.colonisation.ColonisationSystemClaimRelease;
import kairon.observation.journal.event.colonisation.CompleteConstruction;
import kairon.observation.journal.event.combat.Bounty;
import kairon.observation.journal.event.combat.CockpitBreached;
import kairon.observation.journal.event.combat.CommitCrime;
import kairon.observation.journal.event.combat.Died;
import kairon.observation.journal.event.combat.EscapeInterdiction;
import kairon.observation.journal.event.combat.HeatDamage;
import kairon.observation.journal.event.combat.HullDamage;
import kairon.observation.journal.event.combat.Interdicted;
import kairon.observation.journal.event.combat.Interdiction;
import kairon.observation.journal.event.combat.PVPKill;
import kairon.observation.journal.event.combat.SelfDestruct;
import kairon.observation.journal.event.combat.SystemsShutdown;
import kairon.observation.journal.event.combat.UnderAttack;
import kairon.observation.journal.event.engineering.EngineerContribution;
import kairon.observation.journal.event.engineering.EngineerCraft;
import kairon.observation.journal.event.engineering.EngineerLegacyConvert;
import kairon.observation.journal.event.engineering.TechnologyBroker;
import kairon.observation.journal.event.exploration.CodexEntry;
import kairon.observation.journal.event.exploration.FSSAllBodiesFound;
import kairon.observation.journal.event.exploration.FSSBodySignals;
import kairon.observation.journal.event.exploration.MultiSellExplorationData;
import kairon.observation.journal.event.exploration.SAAScanComplete;
import kairon.observation.journal.event.exploration.SAASignalsFound;
import kairon.observation.journal.event.exploration.Scan;
import kairon.observation.journal.event.exploration.ScanOrganic;
import kairon.observation.journal.event.exploration.SellExplorationData;
import kairon.observation.journal.event.exploration.SellOrganicData;
import kairon.observation.journal.event.inventory.CargoTransfer;
import kairon.observation.journal.event.inventory.CollectCargo;
import kairon.observation.journal.event.inventory.EjectCargo;
import kairon.observation.journal.event.inventory.MaterialDiscovered;
import kairon.observation.journal.event.mining.AsteroidCracked;
import kairon.observation.journal.event.mission.CommunityGoalJoin;
import kairon.observation.journal.event.mission.CommunityGoalReward;
import kairon.observation.journal.event.mission.MissionAbandoned;
import kairon.observation.journal.event.mission.MissionAccepted;
import kairon.observation.journal.event.mission.MissionCompleted;
import kairon.observation.journal.event.mission.MissionFailed;
import kairon.observation.journal.event.mission.MissionRedirected;
import kairon.observation.journal.event.onfoot.HoloscreenHacked;
import kairon.observation.journal.event.onfoot.UpgradeSuit;
import kairon.observation.journal.event.onfoot.UpgradeWeapon;
import kairon.observation.journal.event.powerplay.PowerplayDefect;
import kairon.observation.journal.event.powerplay.PowerplayJoin;
import kairon.observation.journal.event.powerplay.PowerplayLeave;
import kairon.observation.journal.event.powerplay.PowerplayRank;
import kairon.observation.journal.event.session.Commander;
import kairon.observation.journal.event.session.NewCommander;
import kairon.observation.journal.event.session.Promotion;
import kairon.observation.journal.event.ship.DockSRV;
import kairon.observation.journal.event.ship.FighterDestroyed;
import kairon.observation.journal.event.ship.LaunchFighter;
import kairon.observation.journal.event.ship.RebootRepair;
import kairon.observation.journal.event.ship.SellShipOnRebuy;
import kairon.observation.journal.event.ship.SetUserShipName;
import kairon.observation.journal.event.ship.ShipRedeemed;
import kairon.observation.journal.event.ship.ShipyardBuy;
import kairon.observation.journal.event.ship.ShipyardNew;
import kairon.observation.journal.event.ship.ShipyardSell;
import kairon.observation.journal.event.ship.ShipyardSwap;
import kairon.observation.journal.event.ship.ShipyardTransfer;
import kairon.observation.journal.event.ship.SRVDestroyed;
import kairon.observation.journal.event.social.CrewFire;
import kairon.observation.journal.event.social.CrewHire;
import kairon.observation.journal.event.social.CrewMemberJoins;
import kairon.observation.journal.event.social.CrewMemberQuits;
import kairon.observation.journal.event.social.Friends;
import kairon.observation.journal.event.social.JoinedSquadron;
import kairon.observation.journal.event.social.KickedFromSquadron;
import kairon.observation.journal.event.social.LeftSquadron;
import kairon.observation.journal.event.social.NpcCrewRank;
import kairon.observation.journal.event.social.ReceiveText;
import kairon.observation.journal.event.social.SquadronCreated;
import kairon.observation.journal.event.social.SquadronDemotion;
import kairon.observation.journal.event.social.SquadronPromotion;
import kairon.observation.journal.event.social.WingJoin;
import kairon.observation.journal.event.social.WingLeave;
import kairon.observation.journal.event.trade.MarketBuy;
import kairon.observation.journal.event.trade.MarketSell;
import kairon.observation.journal.event.trade.RedeemVoucher;
import kairon.observation.journal.event.trade.SearchAndRescue;
import kairon.observation.journal.event.travel.ApproachBody;
import kairon.observation.journal.event.travel.Disembark;
import kairon.observation.journal.event.travel.Docked;
import kairon.observation.journal.event.travel.DockingCancelled;
import kairon.observation.journal.event.travel.DockingDenied;
import kairon.observation.journal.event.travel.DockingTimeout;
import kairon.observation.journal.event.travel.DropshipDeploy;
import kairon.observation.journal.event.travel.Embark;
import kairon.observation.journal.event.travel.FSDJump;
import kairon.observation.journal.event.travel.FSDTarget;
import kairon.observation.journal.event.travel.JetConeBoost;
import kairon.observation.journal.event.travel.JetConeDamage;
import kairon.observation.journal.event.travel.LeaveBody;
import kairon.observation.journal.event.travel.Liftoff;
import kairon.observation.journal.event.travel.Location;
import kairon.observation.journal.event.travel.SupercruiseEntry;
import kairon.observation.journal.event.travel.SupercruiseExit;
import kairon.observation.journal.event.travel.Touchdown;
import kairon.observation.journal.event.travel.Undocked;
import kairon.observation.journal.event.travel.USSDrop;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalEventLlmPresentationTest {

    private final JournalLineParser parser = new JournalLineParser();

    @Test
    void underAttackIdentifiesTechnicalTargetWithoutInventingAttacker() {
        UnderAttack attack = new UnderAttack(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"UnderAttack","Target":"Fighter"}
                """.strip()));

        String presentation = verifiedText(attack.llmPresentation());

        assertTrue(presentation.contains(
                "ship-launched fighter is under fire"
        ));
        assertTrue(presentation.contains(
                "does not identify the attacker or report damage"
        ));
    }

    @Test
    void underAttackWithoutTargetDoesNotInventAVesselKind() {
        UnderAttack attack = new UnderAttack(rawData("""
                {"timestamp":"2025-09-12T19:34:07Z","event":"UnderAttack"}
                """.strip()));

        String presentation = verifiedText(attack.llmPresentation());

        assertTrue(presentation.contains(
                "an unspecified player-controlled target is under fire"
        ));
        assertFalse(presentation.contains("current vessel"));
    }

    @Test
    void engineerContributionExplainsAccessContributionAndTotals() {
        EngineerContribution contribution =
                new EngineerContribution(rawData("""
                        {"timestamp":"2017-05-24T10:41:51Z","event":"EngineerContribution","Engineer":"Elvira Martuuk","EngineerID":300160,"Type":"Commodity","Commodity":"soontillrelics","Commodity_Localised":"Soontill Relics","Quantity":2,"TotalQuantity":3}
                        """.strip()));

        String presentation = verifiedText(
                contribution.llmPresentation()
        );

        assertTrue(presentation.contains(
                "contribution to gain access to an engineer"
        ));
        assertTrue(presentation.contains("“Elvira Martuuk”"));
        assertTrue(presentation.contains(
                "contribution type “Commodity”"
        ));
        assertTrue(presentation.contains(
                "commodity “Soontill Relics”"
        ));
        assertTrue(presentation.contains("amount offered now 2"));
        assertTrue(presentation.contains("total donated 3"));
    }

    @Test
    void engineerCraftExplainsProgressIngredientsAndModifierDirection() {
        EngineerCraft craft = new EngineerCraft(rawData("""
                {"timestamp":"2018-03-04T07:08:27Z","event":"EngineerCraft","Slot":"Slot03_Size3","Module":"int_dronecontrol_collection_size3_class5","Module_Localised":"Collector Limpet Controller","Ingredients":[{"Name":"phosphorus","Name_Localised":"Phosphorus","Count":1}],"Engineer":"Ram Tah","EngineerID":300110,"BlueprintID":128731526,"BlueprintName":"Misc_LightWeight","Level":1,"Quality":0.955,"Modifiers":[{"Label":"Mass","Value":4.436,"OriginalValue":8.0,"LessIsGood":1},{"Label":"Integrity","Value":81.0,"OriginalValue":90.0,"LessIsGood":false}]}
                """.strip()));

        String presentation = verifiedText(craft.llmPresentation());

        assertTrue(presentation.contains(
                "requested an engineer upgrade"
        ));
        assertTrue(presentation.contains(
                "blueprint “Misc_LightWeight”"
        ));
        assertTrue(presentation.contains(
                "blueprint refinement progress 0.955 on the documented "
                        + "0-to-1 scale"
        ));
        assertTrue(presentation.contains(
                "Ingredients consumed: “Phosphorus” x 1"
        ));
        assertTrue(presentation.contains(
                "“Mass”: new value 4.436, original value 8, and lower "
                        + "values are beneficial"
        ));
        assertTrue(presentation.contains(
                "“Integrity”: new value 81, original value 90, and higher "
                        + "values are beneficial"
        ));
        assertFalse(presentation.contains("excellent"));
    }

    @Test
    void legacyConversionDistinguishesPreviewFromActualConversion() {
        EngineerLegacyConvert preview =
                new EngineerLegacyConvert(rawData("""
                        {"timestamp":"2018-03-04T07:08:27Z","event":"EngineerLegacyConvert","IsPreview":true,"Module":"int_hyperdrive_size5_class5","Module_Localised":"Frame Shift Drive","BlueprintName":"FSD_LongRange","Level":4,"Quality":0.7}
                        """.strip()));
        EngineerLegacyConvert converted =
                new EngineerLegacyConvert(rawData("""
                        {"timestamp":"2018-03-04T07:08:28Z","event":"EngineerLegacyConvert","IsPreview":false,"Module":"int_hyperdrive_size5_class5","Module_Localised":"Frame Shift Drive","BlueprintName":"FSD_LongRange","Level":4,"Quality":0.7}
                        """.strip()));

        String previewText = verifiedText(preview.llmPresentation());
        String convertedText = verifiedText(converted.llmPresentation());

        assertTrue(previewText.contains(
                "previewed conversion of a legacy engineered module"
        ));
        assertTrue(previewText.contains(
                "does not establish that conversion occurred"
        ));
        assertTrue(convertedText.contains(
                "converted a legacy engineered module"
        ));
        assertFalse(convertedText.contains("previewed"));
    }

    @Test
    void technologyBrokerListsUnlockedItemsAndExactCosts() {
        TechnologyBroker broker = new TechnologyBroker(rawData("""
                {"timestamp":"2018-03-02T11:28:44Z","event":"TechnologyBroker","BrokerType":"Human","MarketID":128151032,"ItemsUnlocked":[{"Name":"Hpt_PlasmaShockCannon_Fixed_Medium","Name_Localised":"Shock Cannon"}],"Commodities":[{"Name":"iondistributor","Name_Localised":"Ion Distributor","Count":6}],"Materials":[{"Name":"vanadium","Name_Localised":"Vanadium","Count":30,"Category":"Raw"}]}
                """.strip()));

        String presentation = verifiedText(broker.llmPresentation());

        assertTrue(presentation.contains(
                "technology that can now be purchased"
        ));
        assertTrue(presentation.contains(
                "Items unlocked: “Shock Cannon”"
        ));
        assertTrue(presentation.contains(
                "Commodities used: “Ion Distributor” x 6"
        ));
        assertTrue(presentation.contains(
                "Materials used: “Vanadium” x 30 (category “Raw”)"
        ));
    }

    @Test
    void interdictedKeepsDirectionSubmissionAndInterdictorIdentityClear() {
        Interdicted interdicted = new Interdicted(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"Interdicted","Submitted":false,"Interdictor":"Dread Pirate Roberts","IsPlayer":false,"IsThargoid":false,"Faction":"Timocani Purple Posse","CombatRank":5}
                """.strip()));

        String presentation = verifiedText(
                interdicted.llmPresentation()
        );

        assertTrue(presentation.contains("The player was interdicted"));
        assertTrue(presentation.contains(
                "did not submit to the interdiction"
        ));
        assertTrue(presentation.contains("interdictor as an NPC"));
        assertTrue(presentation.contains(
                "faction “Timocani Purple Posse”"
        ));
        assertTrue(presentation.contains(
                "combat-rank value 5 on the 0-to-8 scale"
        ));
    }

    @Test
    void interdictionKeepsPlayerAsInitiatorAndReportsOutcome() {
        Interdiction interdiction = new Interdiction(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"Interdiction","Success":true,"Interdicted":"Fred Flintstone","IsPlayer":true,"CombatRank":5}
                """.strip()));

        String presentation = verifiedText(
                interdiction.llmPresentation()
        );

        assertTrue(presentation.contains(
                "player attempted to interdict another pilot "
                        + "“Fred Flintstone”"
        ));
        assertTrue(presentation.contains("interdiction succeeded"));
        assertTrue(presentation.contains(
                "target is identified as another player"
        ));
        assertFalse(presentation.contains("player was interdicted"));
    }

    @Test
    void pvpKillReportsVictimAndDocumentedRankScale() {
        PVPKill kill = new PVPKill(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"PVPKill","Victim":"Cmdr Example","CombatRank":7}
                """.strip()));

        String presentation = verifiedText(kill.llmPresentation());

        assertTrue(presentation.contains(
                "player killed another player, “Cmdr Example”"
        ));
        assertTrue(presentation.contains(
                "victim's combat-rank value is 7"
        ));
        assertTrue(presentation.contains(
                "documented 0-to-8 scale"
        ));
    }

    @Test
    void selfDestructDoesNotInventMotiveOrVessel() {
        SelfDestruct selfDestruct = new SelfDestruct(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"SelfDestruct"}
                """.strip()));

        String presentation = verifiedText(
                selfDestruct.llmPresentation()
        );

        assertTrue(presentation.contains(
                "used the self-destruct function"
        ));
        assertTrue(presentation.contains(
                "no vessel identity or reason"
        ));
    }

    @Test
    void systemsShutdownDoesNotAssumeThargoidCause() {
        SystemsShutdown shutdown = new SystemsShutdown(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"SystemsShutdown"}
                """.strip()));

        String presentation = verifiedText(shutdown.llmPresentation());

        assertTrue(presentation.contains("ship systems shut down"));
        assertTrue(presentation.contains(
                "does not identify what caused the shutdown"
        ));
        assertFalse(presentation.contains("Thargoid"));
    }

    @Test
    void commitCrimeReportsRecordedPenaltyWithoutInferringIntent() {
        CommitCrime crime = new CommitCrime(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"CommitCrime","CrimeType":"assault","Faction":"The Pilots Federation","Victim":"Potapinski","Bounty":210}
                """.strip()));

        String presentation = verifiedText(crime.llmPresentation());

        assertTrue(presentation.contains(
                "crime was recorded against the player"
        ));
        assertTrue(presentation.contains(
                "crime-type identifier “assault”"
        ));
        assertTrue(presentation.contains(
                "faction “The Pilots Federation”"
        ));
        assertTrue(presentation.contains("victim “Potapinski”"));
        assertTrue(presentation.contains(
                "recorded bounty is 210 credits"
        ));
        assertFalse(presentation.contains("intentional"));
    }

    @Test
    void diedSupportsSingleKillerAndKillerWingForms() {
        Died single = new Died(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"Died","KillerName":"$ShipName_Police_Independent;","KillerName_Localised":"System Authority Vessel","KillerShip":"viper","KillerRank":"Deadly"}
                """.strip()));
        Died wing = new Died(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"Died","Killers":[{"Name":"Cmdr HRC1","Ship":"Vulture","Rank":"Competent"},{"Name":"Cmdr HRC2","Ship":"Python","Rank":"Master"}]}
                """.strip()));

        String singleText = verifiedText(single.llmPresentation());
        String wingText = verifiedText(wing.llmPresentation());

        assertTrue(singleText.contains("The player was killed"));
        assertTrue(singleText.contains(
                "name “System Authority Vessel”"
        ));
        assertTrue(singleText.contains("ship type “viper”"));
        assertTrue(singleText.contains("combat rank “Deadly”"));
        assertTrue(wingText.contains("killer wing members"));
        assertTrue(wingText.contains("name “Cmdr HRC1”"));
        assertTrue(wingText.contains("ship type “Python”"));
    }

    @Test
    void escapeInterdictionReportsOutcomeAndTechnicalIdentity() {
        EscapeInterdiction escape = new EscapeInterdiction(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"EscapeInterdiction","Interdictor":"Dread Pirate Roberts","IsPlayer":false,"IsThargoid":false}
                """.strip()));

        String presentation = verifiedText(escape.llmPresentation());

        assertTrue(presentation.contains(
                "escaped an interdiction attempt"
        ));
        assertTrue(presentation.contains(
                "by “Dread Pirate Roberts”"
        ));
        assertTrue(presentation.contains(
                "interdictor as an NPC and not a Thargoid"
        ));
        assertFalse(presentation.contains("easy"));
    }

    @Test
    void heatDamageDoesNotInventSeverityOrModuleDamage() {
        HeatDamage heat = new HeatDamage(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"HeatDamage","ID":1}
                """.strip()));

        String presentation = verifiedText(heat.llmPresentation());

        assertTrue(presentation.contains("damage from overheating"));
        assertTrue(presentation.contains(
                "does not report the damage amount"
        ));
        assertTrue(presentation.contains(
                "current heat level, or damaged modules"
        ));
        assertFalse(presentation.contains("severe"));
    }

    @Test
    void hullDamageExplainsThresholdAndVesselFlagsWithoutSeverityLabel() {
        HullDamage damage = new HullDamage(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"HullDamage","Health":0.398,"PlayerPilot":true,"Fighter":false}
                """.strip()));

        String presentation = verifiedText(damage.llmPresentation());

        assertTrue(presentation.contains(
                "20-percentage-point damage thresholds"
        ));
        assertTrue(presentation.contains(
                "hull-health source value is 0.398"
        ));
        assertTrue(presentation.contains(
                "not a ship-launched fighter"
        ));
        assertTrue(presentation.contains("the player is piloting it"));
        assertFalse(presentation.contains("critical"));
    }

    @Test
    void colonisationClaimDoesNotImplyConstructionStarted() {
        ColonisationSystemClaim claim =
                new ColonisationSystemClaim(rawData("""
                        {"timestamp":"2025-04-18T12:00:00Z","event":"ColonisationSystemClaim","StarSystem":"Panoi","SystemAddress":6955800204002}
                        """.strip()));

        String presentation = verifiedText(claim.llmPresentation());

        assertTrue(presentation.contains(
                "paid to claim a star system for colonisation"
        ));
        assertTrue(presentation.contains("star system “Panoi”"));
        assertTrue(presentation.contains(
                "star-system address 6955800204002"
        ));
        assertTrue(presentation.contains(
                "does not report that construction has started"
        ));
    }

    @Test
    void colonisationClaimReleaseDoesNotInventReasonOrRefund() {
        ColonisationSystemClaimRelease release =
                new ColonisationSystemClaimRelease(rawData("""
                        {"timestamp":"2025-04-18T12:00:00Z","event":"ColonisationSystemClaimRelease","StarSystem":"Panoi","SystemAddress":6955800204002}
                        """.strip()));

        String presentation = verifiedText(release.llmPresentation());

        assertTrue(presentation.contains(
                "colonisation claim was released"
        ));
        assertTrue(presentation.contains("star system “Panoi”"));
        assertTrue(presentation.contains(
                "does not report the reason or any refund"
        ));
    }

    @Test
    void completedConstructionDoesNotInventFacilityIdentity() {
        CompleteConstruction completion =
                new CompleteConstruction(rawData("""
                        {"timestamp":"2025-04-18T12:00:00Z","event":"CompleteConstruction"}
                        """.strip()));

        assertEquals(
                "A colonisation construction was completed. This event "
                        + "contains no construction, market, system, or "
                        + "facility identifier.",
                verifiedText(completion.llmPresentation())
        );
    }

    @Test
    void bountyExplainsRewardIssuersAndSharedKillCredit() {
        Bounty bounty = new Bounty(rawData("""
                {"timestamp":"2018-04-17T11:11:02Z","event":"Bounty","Rewards":[{"Faction":"Nehet Patron's Principles","Reward":5620},{"Faction":"Pilots Federation","Reward":400}],"Target":"empire_eagle","Target_Localised":"Imperial Eagle","TotalReward":6020,"VictimFaction":"Nehet Progressive Party","SharedWithOthers":1}
                """.strip()));

        String presentation = verifiedText(bounty.llmPresentation());

        assertTrue(presentation.contains(
                "awarded a bounty for a kill"
        ));
        assertTrue(presentation.contains(
                "target type “Imperial Eagle”"
        ));
        assertTrue(presentation.contains(
                "affiliated with “Nehet Progressive Party”"
        ));
        assertTrue(presentation.contains(
                "total bounty reward is 6,020 credits"
        ));
        assertTrue(presentation.contains(
                "“Nehet Patron's Principles” paying 5,620 credits"
        ));
        assertTrue(presentation.contains(
                "“Pilots Federation” paying 400 credits"
        ));
        assertTrue(presentation.contains(
                "shared with 1 other player"
        ));
        assertFalse(presentation.contains("rare"));
    }

    @Test
    void cockpitBreachDoesNotInventCauseOrRemainingTime() {
        CockpitBreached breach = new CockpitBreached(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"CockpitBreached"}
                """.strip()));

        String presentation = verifiedText(breach.llmPresentation());

        assertTrue(presentation.contains(
                "cockpit canopy was breached"
        ));
        assertTrue(presentation.contains(
                "reports no cause, damage amount, or remaining "
                        + "life-support time"
        ));
    }

    @Test
    void carrierJumpRequestDistinguishesRequestFromCompletedJump() {
        CarrierJumpRequest request = new CarrierJumpRequest(rawData("""
                {"timestamp":"2020-04-20T09:30:58Z","event":"CarrierJumpRequest","CarrierID":3700005632,"SystemName":"Paesui Xena","SystemAddress":7269634680241,"Body":"Paesui Xena A","BodyID":1,"DepartureTime":"2020-04-20T09:45:00Z"}
                """.strip()));

        String presentation = verifiedText(request.llmPresentation());

        assertTrue(presentation.contains(
                "requested a fleet-carrier jump"
        ));
        assertTrue(presentation.contains(
                "records the request, not the completed jump"
        ));
        assertTrue(presentation.contains("star system “Paesui Xena”"));
        assertTrue(presentation.contains("body ID 1"));
        assertTrue(presentation.contains(
                "requested departure time is "
                        + "“2020-04-20T09:45:00Z”"
        ));
        assertTrue(presentation.contains(
                "destination star-system address is 7269634680241"
        ));
    }

    @Test
    void carrierNameChangeDoesNotInventPreviousName() {
        CarrierNameChange change = new CarrierNameChange(rawData("""
                {"timestamp":"2020-05-01T12:00:00Z","event":"CarrierNameChange","CarrierID":3700005632,"Callsign":"L14-X1J","Name":"Spirula"}
                """.strip()));

        String presentation = verifiedText(change.llmPresentation());

        assertTrue(presentation.contains(
                "changed a fleet carrier's name to “Spirula”"
        ));
        assertTrue(presentation.contains("callsign “L14-X1J”"));
        assertTrue(presentation.contains("carrier market ID 3700005632"));
        assertTrue(presentation.contains(
                "does not report the previous name"
        ));
    }

    @Test
    void colonisationBeaconStatesOnlyWhatTheEmptyEventEstablishes() {
        ColonisationBeaconDeployed beacon =
                new ColonisationBeaconDeployed(rawData("""
                        {"timestamp":"2025-04-18T12:00:00Z","event":"ColonisationBeaconDeployed"}
                        """.strip()));

        String presentation = verifiedText(beacon.llmPresentation());

        assertEquals(
                "A colonisation beacon was deployed. This event contains no "
                        + "system, body, owner, or beacon identifier.",
                presentation
        );
        assertFalse(presentation.contains("player's"));
    }

    @Test
    void constructionDepotKeepsUndocumentedNumbersUninterpreted() {
        ColonisationConstructionDepot snapshot =
                new ColonisationConstructionDepot(rawData("""
                        {"timestamp":"2025-04-18T12:00:00Z","event":"ColonisationConstructionDepot","MarketID":4217038595,"ConstructionProgress":0.141462,"ConstructionComplete":false,"ConstructionFailed":false,"ResourcesRequired":[{"Name":"$aluminium_name;","Name_Localised":"Aluminium","RequiredAmount":43618,"ProvidedAmount":621,"Payment":3239}]}
                        """.strip()));

        String presentation = verifiedText(snapshot.llmPresentation());

        assertTrue(presentation.contains(
                "periodic status snapshot while the player was docked"
        ));
        assertTrue(presentation.contains(
                "construction progress source value 0.141462"
        ));
        assertTrue(presentation.contains(
                "construction not marked complete"
        ));
        assertTrue(presentation.contains(
                "construction not marked failed"
        ));
        assertTrue(presentation.contains(
                "“Aluminium”: 621 provided, 43,618 required"
        ));
        assertTrue(presentation.contains(
                "listed payment source value 3,239"
        ));
        assertFalse(presentation.contains("14.1462%"));
        assertFalse(presentation.contains("credits"));
    }

    @Test
    void colonisationContributionListsExactMaterialsWithoutAssumedUnits() {
        ColonisationContribution contribution =
                new ColonisationContribution(rawData("""
                        {"timestamp":"2025-04-18T12:00:00Z","event":"ColonisationContribution","MarketID":3705689344,"Contributions":[{"Name":"$Aluminium_name;","Name_Localised":"Aluminium","Amount":724},{"Name":"$Water_name;","Name_Localised":"Water","Amount":12}]}
                        """.strip()));

        String presentation = verifiedText(
                contribution.llmPresentation()
        );

        assertTrue(presentation.contains(
                "contributed materials to a colonisation effort"
        ));
        assertTrue(presentation.contains(
                "construction market ID 3705689344"
        ));
        assertTrue(presentation.contains(
                "“Aluminium”: source amount 724"
        ));
        assertTrue(presentation.contains(
                "“Water”: source amount 12"
        ));
        assertTrue(presentation.contains(
                "does not define a unit for these amounts"
        ));
        assertFalse(presentation.contains("ton"));
    }

    @Test
    void carrierBuyExplainsPurchaseWithoutInterpretingVariantIdentifier() {
        String rawJson = """
                {"timestamp":"2020-03-11T15:31:46Z","event":"CarrierBuy","CarrierID":3700029440,"BoughtAtMarket":3221301504,"Location":"Kakmbutan","SystemAddress":3549513615723,"Price":4875000000,"Variant":"CarrierDockB","Callsign":"P07-V3L"}
                """.strip();
        CarrierBuy purchase = new CarrierBuy(rawData(rawJson));

        String presentation = verifiedText(purchase.llmPresentation());

        assertEquals(
                "The player bought a fleet carrier with callsign “P07-V3L” "
                        + "(carrier market ID 3700029440) in star system "
                        + "“Kakmbutan”.",
                purchase.llmPresentation().sentences().getFirst()
        );
        assertTrue(presentation.contains("4,875,000,000 credits"));
        assertTrue(presentation.contains("purchase market ID 3221301504"));
        assertTrue(presentation.contains(
                "variant identifier is “CarrierDockB”"
        ));
        assertTrue(presentation.contains(
                "identifier alone does not describe the variant's capabilities"
        ));
        assertFalse(presentation.contains("premium"));
        assertFalse(presentation.contains("better"));
        assertEquals(rawJson, purchase.raw().rawJson());
    }

    @Test
    void carrierDecommissionDistinguishesRequestFromCompletedScrapping() {
        CarrierDecommission request = new CarrierDecommission(rawData("""
                {"timestamp":"2020-03-11T15:12:26Z","event":"CarrierDecommission","CarrierID":3700005632,"ScrapRefund":1746872629,"ScrapTime":1584601200}
                """.strip()));

        String presentation = verifiedText(request.llmPresentation());

        assertTrue(presentation.contains(
                "requested decommissioning of a fleet carrier"
        ));
        assertTrue(presentation.contains(
                "records the request, not completed decommissioning"
        ));
        assertTrue(presentation.contains("1,746,872,629 credits"));
        assertTrue(presentation.contains("numeric source timestamp 1584601200"));
        assertTrue(presentation.contains("does not define that number's epoch"));
        assertFalse(presentation.contains("has been scrapped"));
    }

    @Test
    void carrierCancelDecommissionExplainsCancellationOnly() {
        CarrierCancelDecommission cancellation =
                new CarrierCancelDecommission(rawData("""
                        {"timestamp":"2020-03-11T15:12:38Z","event":"CarrierCancelDecommission","CarrierID":3700005632}
                        """.strip()));

        assertEquals(
                "The player cancelled a pending fleet-carrier "
                        + "decommissioning request for carrier market ID "
                        + "3700005632.",
                verifiedText(cancellation.llmPresentation())
        );
    }

    @Test
    void carrierJumpExplainsArrivalAndExplicitlyMissingJumpMetrics() {
        String rawJson = """
                {"timestamp":"2026-07-29T12:00:00Z","event":"CarrierJump","Docked":true,"StationName":"FC L14X1J","StationType":"FleetCarrier","MarketID":3700005632,"StarSystem":"Hermitage","SystemAddress":5363877956440,"SystemAllegiance":"Independent","SystemEconomy":"$economy_Extraction;","SystemEconomy_Localised":"Extraction","SystemSecondEconomy":"$economy_None;","SystemSecondEconomy_Localised":"None","SystemGovernment":"$government_Anarchy;","SystemGovernment_Localised":"Anarchy","SystemSecurity":"$GAlAXY_MAP_INFO_state_anarchy;","SystemSecurity_Localised":"Anarchy","Population":0,"Body":"Hermitage","BodyID":0,"BodyType":"Star","ThargoidWar":{"CurrentState":"Invasion","NextStateSuccess":"Recovery","NextStateFailure":"Controlled","SuccessStateReached":false,"WarProgress":0.4,"RemainingPorts":2,"EstimatedRemainingTime":86400}}
                """.strip();
        CarrierJump jump = new CarrierJump(rawData(rawJson));

        String presentation = verifiedText(jump.llmPresentation());

        assertTrue(presentation.contains(
                "fleet-carrier hyperspace jump by carrier “FC L14X1J”"
        ));
        assertTrue(presentation.contains("to star system “Hermitage”"));
        assertTrue(presentation.contains(
                "The player was docked at this fleet carrier during the jump"
        ));
        assertTrue(presentation.contains("body type “Star”"));
        assertTrue(presentation.contains("primary economy “Extraction”"));
        assertTrue(presentation.contains(
                "destination system is affected by the Thargoid war"
        ));
        assertTrue(presentation.contains(
                "war progress 0.4 on a 0-to-1 scale"
        ));
        assertTrue(presentation.contains("remaining ports 2"));
        assertTrue(presentation.contains(
                "does not report the distance jumped or the fuel used"
        ));
        assertFalse(presentation.contains("dangerous"));
        assertFalse(presentation.contains("long jump"));
        assertEquals(rawJson, jump.raw().rawJson());
    }

    @Test
    void carrierJumpCancelledDoesNotInventDestinationOrReason() {
        CarrierJumpCancelled cancellation = new CarrierJumpCancelled(rawData("""
                {"timestamp":"2020-05-01T12:00:00Z","event":"CarrierJumpCancelled","CarrierID":3700005632}
                """.strip()));

        String presentation = verifiedText(cancellation.llmPresentation());

        assertEquals(
                "A scheduled fleet-carrier jump was cancelled for carrier "
                        + "market ID 3700005632; this event does not report "
                        + "a destination or cancellation reason.",
                presentation
        );
        assertFalse(presentation.contains("because"));
    }

    @Test
    void scanExplainsDocumentedBodyAndOccurrenceFactsWithoutInventingRarity() {
        String rawJson = """
                {"timestamp":"2026-07-24T16:45:15Z","event":"Scan","ScanType":"Detailed","BodyName":"Schieni GG-A c3-84 4 a","BodyID":20,"StarSystem":"Schieni GG-A c3-84","DistanceFromArrivalLS":1081.453145,"PlanetClass":"Icy body","Atmosphere":"thin methane atmosphere","AtmosphereComposition":[{"Name":"Methane","Percent":100.0}],"MassEM":0.000180,"Radius":499610.3125,"SurfaceGravity":0.287597,"SurfaceTemperature":95.505936,"SurfacePressure":4155.584473,"Landable":true,"TidalLock":true,"Composition":{"Ice":0.823731,"Rock":0.175197,"Metal":0.001072},"Materials":[{"Name":"sulphur","Name_Localised":"Sulphur","Percent":27.813265},{"Name":"yttrium","Name_Localised":"Yttrium","Percent":0.752182},{"Name":"invalid","Name_Localised":"Invalid","Percent":-1}],"WasDiscovered":false,"WasMapped":false}
                """.strip();

        Scan scan = new Scan(rawData(rawJson));
        String presentation = verifiedText(scan.llmPresentation());

        assertTrue(presentation.contains("a detailed discovery scan"));
        assertTrue(presentation.contains("planet or moon class “Icy body”"));
        assertTrue(presentation.contains(
                "surface gravity 0.287597 metres per second squared "
                        + "(0.02933 g)"
        ));
        assertTrue(presentation.contains(
                "surface temperature 95.505936 kelvins "
                        + "(-177.644 degrees Celsius)"
        ));
        assertTrue(presentation.contains(
                "surface pressure 4155.584473 pascals "
                        + "(4.156 kilopascals)"
        ));
        assertTrue(presentation.contains("mass 0.00018 Earth masses"));
        assertTrue(presentation.contains("radius 499.61 kilometres"));
        assertTrue(presentation.contains(
                "distance from the arrival point 1081.453145 light-seconds"
        ));
        assertTrue(presentation.contains(
                "reported atmospheric composition is “Methane” at 100%"
        ));
        assertTrue(presentation.contains(
                "bulk composition is ice 82.3731%, rock 17.5197%, "
                        + "and metal 0.1072%"
        ));
        assertTrue(presentation.contains("“Sulphur” at 27.813265%"));
        assertTrue(presentation.contains("“Yttrium” at 0.752182%"));
        assertTrue(presentation.contains("material occurrence percentages"));
        assertTrue(presentation.contains("had not been discovered"));
        assertTrue(presentation.contains("had not been mapped"));
        assertFalse(presentation.contains("WasDiscovered"));
        assertFalse(presentation.contains("“Invalid”"));
        assertFalse(presentation.contains("Yttrium is rare"));
        assertEquals(rawJson, scan.raw().rawJson());
    }

    @Test
    void approachBodyExplainsOrbitalCruiseEntryWithoutInventingConditions() {
        ApproachBody approach = new ApproachBody(rawData("""
                {"timestamp":"2016-07-22T10:53:19Z","event":"ApproachBody","StarSystem":"Eranin","SystemAddress":2832631632594,"Body":"Eranin 2","BodyID":2}
                """.strip()));

        String presentation = verifiedText(approach.llmPresentation());

        assertTrue(presentation.contains(
                "approached planet or moon “Eranin 2”"
        ));
        assertTrue(presentation.contains("entered its orbital-cruise zone"));
        assertTrue(presentation.contains("system “Eranin”"));
        assertTrue(presentation.contains("body ID 2"));
        assertFalse(presentation.contains("temperature"));
        assertFalse(presentation.contains("gravity"));
        assertFalse(presentation.contains("important"));
    }

    @Test
    void saaSignalsDistinguishesSignalCountsFromBiologicalGenuses() {
        SAASignalsFound signals = new SAASignalsFound(rawData("""
                {"timestamp":"2026-07-24T16:45:15Z","event":"SAASignalsFound","BodyName":"Schieni GG-A c3-84 4 a","BodyID":20,"Signals":[{"Type":"$SAA_SignalType_Biological;","Type_Localised":"Biological","Count":1}],"Genuses":[{"Genus":"$Codex_Ent_Bacterial_Genus_Name;","Genus_Localised":"Bacterium"}]}
                """.strip()));

        String presentation = verifiedText(signals.llmPresentation());

        assertTrue(presentation.contains("on target “Schieni GG-A c3-84 4 a”"));
        assertTrue(presentation.contains("“Biological”: 1"));
        assertTrue(presentation.contains("biological genera"));
        assertTrue(presentation.contains("“Bacterium”"));
        assertFalse(presentation.contains("species"));
        assertFalse(presentation.contains("rare"));

        SAASignalsFound opaqueLocalized = new SAASignalsFound(rawData("""
                {"event":"SAASignalsFound","Genuses":[{"Genus":"$internal;","Genus_Localised":"$still_internal;"}]}
                """.strip()));
        assertFalse(
                opaqueLocalized.llmPresentation().text().contains("$internal")
        );
    }

    @Test
    void saaCompletionReportsProbeNumbersWithoutClaimingEfficiencyOutcome() {
        SAAScanComplete completion = new SAAScanComplete(rawData("""
                {"timestamp":"2026-07-24T16:45:15Z","event":"SAAScanComplete","BodyName":"Schieni GG-A c3-84 4 a","BodyID":20,"ProbesUsed":2,"EfficiencyTarget":2}
                """.strip()));

        String presentation = verifiedText(completion.llmPresentation());

        assertEquals(
                "The journal recorded completion of Surface Area Analysis "
                        + "for “Schieni GG-A c3-84 4 a” (body ID 20).",
                completion.llmPresentation().sentences().getFirst()
        );
        assertTrue(presentation.contains("2 probes used"));
        assertTrue(presentation.contains("efficiency target of 2 probes"));
        assertFalse(presentation.contains("met the"));
        assertFalse(presentation.contains("exceeded"));
        assertFalse(presentation.contains("bonus"));
    }

    @Test
    void codexEntryExplainsOnlyExplicitNoveltyAndLocationFacts() {
        CodexEntry entry = new CodexEntry(rawData("""
                {"timestamp":"2026-07-24T16:53:01Z","event":"CodexEntry","EntryID":2321006,"Name":"$Codex_Ent_Bacterial_10_Yttrium_Name;","Name_Localised":"Bacterium Bullaris - Red","SubCategory":"$Codex_SubCategory_Organic_Structures;","SubCategory_Localised":"Organic structures","Category":"$Codex_Category_Biology;","Category_Localised":"Biological and geological","Region":"$Codex_RegionName_9;","Region_Localised":"Inner Scutum-Centaurus Arm","System":"Schieni GG-A c3-84","BodyID":20,"NearestDestination":"$SAA_Unknown_Signal;","NearestDestination_Localised":"Surface signal: Biological (1)","Latitude":18.766066,"Longitude":-35.084538,"IsNewEntry":true}
                """.strip()));

        String presentation = verifiedText(entry.llmPresentation());

        assertTrue(presentation.contains("“Bacterium Bullaris - Red”"));
        assertTrue(presentation.contains("category “Biological and geological”"));
        assertTrue(presentation.contains("region “Inner Scutum-Centaurus Arm”"));
        assertTrue(presentation.contains(
                "nearest listed navigation-panel location "
                        + "“Surface signal: Biological (1)”"
        ));
        assertTrue(presentation.contains("this is a new Codex entry"));
        assertFalse(presentation.contains("globally"));
        assertFalse(presentation.contains("rare"));
        assertFalse(presentation.contains("valuable"));
    }

    @Test
    void organicStagesHaveDistinctDocumentedMeaningsAndIgnoreWasLogged() {
        ScanOrganic logged = organic("Log", false);
        ScanOrganic sampled = organic("Sample", true);
        ScanOrganic analysed = organic("Analyse", true);

        String logText = verifiedText(logged.llmPresentation());
        String sampleText = verifiedText(sampled.llmPresentation());
        String analyseText = verifiedText(analysed.llmPresentation());

        assertTrue(logText.contains("first scan"));
        assertTrue(logText.contains("not yet complete"));
        assertTrue(sampleText.contains("subsequent sample"));
        assertTrue(sampleText.contains("not yet complete"));
        assertTrue(analyseText.contains("final scan"));
        assertTrue(analyseText.contains("completed the sampling sequence"));
        assertFalse(logText.contains("WasLogged"));
        assertFalse(sampleText.contains("WasLogged"));
        assertFalse(analyseText.contains("WasLogged"));
        assertFalse(analyseText.contains("rare"));
    }

    @Test
    void fssAllBodiesFoundReportsCompletionAndDocumentedCount() {
        FSSAllBodiesFound completion = new FSSAllBodiesFound(rawData("""
                {"timestamp":"2018-10-05T11:33:04Z","event":"FSSAllBodiesFound","SystemName":"Eranin","SystemAddress":2832631632594,"Count":12}
                """.strip()));

        String presentation = verifiedText(completion.llmPresentation());

        assertTrue(presentation.contains(
                "identified all bodies in a star system"
        ));
        assertTrue(presentation.contains("Eranin"));
        assertTrue(presentation.contains("12 identified bodies"));
        assertFalse(presentation.contains("valuable"));
    }

    @Test
    void multiSellExplorationDataSeparatesSaleValues() {
        MultiSellExplorationData sale = new MultiSellExplorationData(rawData("""
                {"timestamp":"2018-10-05T11:33:04Z","event":"MultiSellExplorationData","Discovered":[{"SystemName":"HIP 17125","NumBodies":3},{"SystemName":"HIP 17126","NumBodies":1}],"BaseValue":1000,"Bonus":500,"TotalEarnings":1750}
                """.strip()));

        String presentation = verifiedText(sale.llmPresentation());

        assertTrue(presentation.contains(
                "sold one page of exploration data"
        ));
        assertTrue(presentation.contains("HIP 17125"));
        assertTrue(presentation.contains("3 bodies"));
        assertTrue(presentation.contains("1 body"));
        assertTrue(presentation.contains("base value 1,000 credits"));
        assertTrue(presentation.contains(
                "first-discovery bonus 500 credits"
        ));
        assertTrue(presentation.contains("total earnings 1,750 credits"));
    }

    @Test
    void sellExplorationDataPreservesDocumentedListsAndAmounts() {
        SellExplorationData sale = new SellExplorationData(rawData("""
                {"timestamp":"2016-07-21T14:40:39Z","event":"SellExplorationData","Systems":["HIP 17125"],"Discovered":["HIP 17125 A 1"],"BaseValue":10822,"Bonus":3959,"TotalEarnings":14781}
                """.strip()));

        String presentation = verifiedText(sale.llmPresentation());

        assertTrue(presentation.contains("sold exploration data"));
        assertTrue(presentation.contains("HIP 17125"));
        assertTrue(presentation.contains("HIP 17125 A 1"));
        assertTrue(presentation.contains(
                "system base value 10,822 credits"
        ));
        assertTrue(presentation.contains(
                "first-discovery bonus 3,959 credits"
        ));
        assertTrue(presentation.contains(
                "total credits received 14,781 credits"
        ));
    }

    @Test
    void sellOrganicDataReportsIdentityAndCreditsWithoutRarityClaim() {
        SellOrganicData sale = new SellOrganicData(rawData("""
                {"timestamp":"2022-07-18T14:15:18Z","event":"SellOrganicData","MarketID":3228964864,"BioData":[{"Genus":"$Codex_Ent_Bacterial_Genus_Name;","Genus_Localised":"Bacterium","Species":"$Codex_Ent_Bacterial_01_Name;","Species_Localised":"Bacterium Aurasus","Variant":"$Codex_Ent_Bacterial_01_A_Name;","Variant_Localised":"Bacterium Aurasus - Teal","Value":1000000,"Bonus":4000000}]}
                """.strip()));

        String presentation = verifiedText(sale.llmPresentation());

        assertTrue(presentation.contains("market ID 3228964864"));
        assertTrue(presentation.contains("Bacterium Aurasus - Teal"));
        assertTrue(presentation.contains("base value 1,000,000 credits"));
        assertTrue(presentation.contains("bonus 4,000,000 credits"));
        assertFalse(presentation.contains("rare"));
        assertFalse(presentation.contains("valuable"));
    }

    @Test
    void cargoTransferExplainsEachDocumentedDirection() {
        CargoTransfer transfer = new CargoTransfer(rawData("""
                {"timestamp":"2021-03-04T12:12:12Z","event":"CargoTransfer","Transfers":[{"Type":"gold","Type_Localised":"Gold","Count":2,"Direction":"tocarrier"},{"Type":"silver","Type_Localised":"Silver","Count":1,"Direction":"tosrv"},{"Type":"water","Type_Localised":"Water","Count":3,"Direction":"toship"}]}
                """.strip()));

        String presentation = verifiedText(transfer.llmPresentation());

        assertTrue(presentation.contains(
                "2 units of"
        ));
        assertTrue(presentation.contains(
                "from the ship to a fleet carrier"
        ));
        assertTrue(presentation.contains(
                "1 unit of"
        ));
        assertTrue(presentation.contains("from the ship to an SRV"));
        assertTrue(presentation.contains("to the ship"));
    }

    @Test
    void collectCargoExplainsScoopingAndSourceFlags() {
        CollectCargo collection = new CollectCargo(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"CollectCargo","Type":"agriculturalmedicines","Type_Localised":"Agricultural Medicines","Stolen":false,"MissionID":65397935}
                """.strip()));

        String presentation = verifiedText(collection.llmPresentation());

        assertTrue(presentation.contains(
                "scooped cargo"
        ));
        assertTrue(presentation.contains("Agricultural Medicines"));
        assertTrue(presentation.contains(
                "does not mark it as stolen"
        ));
        assertTrue(presentation.contains("mission ID 65397935"));
        assertFalse(presentation.contains("bought"));
    }

    @Test
    void ejectCargoSeparatesQuantityAbandonmentAndPowerplayOrigin() {
        EjectCargo ejection = new EjectCargo(rawData("""
                {"timestamp":"2016-09-21T14:18:23Z","event":"EjectCargo","Type":"alliancelegaslativerecords","Type_Localised":"Alliance Legislative Records","Count":2,"Abandoned":true,"PowerplayOrigin":"Tau Bootis"}
                """.strip()));

        String presentation = verifiedText(ejection.llmPresentation());

        assertTrue(presentation.contains(
                "ejected 2 units of cargo"
        ));
        assertTrue(presentation.contains(
                "Alliance Legislative Records"
        ));
        assertTrue(presentation.contains("marked as abandoned"));
        assertTrue(presentation.contains(
                "Powerplay origin system"
        ));
        assertTrue(presentation.contains("Tau Bootis"));
    }

    @Test
    void materialDiscoveredDoesNotTreatDiscoveryNumberAsQuality() {
        MaterialDiscovered discovery = new MaterialDiscovered(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"MaterialDiscovered","Category":"Manufactured","Name":"focuscrystals","Name_Localised":"Focus Crystals","DiscoveryNumber":3}
                """.strip()));

        String presentation = verifiedText(discovery.llmPresentation());

        assertTrue(presentation.contains(
                "discovered a new material"
        ));
        assertTrue(presentation.contains("Focus Crystals"));
        assertTrue(presentation.contains("category"));
        assertTrue(presentation.contains("Manufactured"));
        assertTrue(presentation.contains("source discovery number 3"));
        assertTrue(presentation.contains(
                "not a rarity or quality rating"
        ));
        assertFalse(presentation.contains("rare material"));
    }

    @Test
    void asteroidCrackedStatesMiningMilestoneWithoutInventingContents() {
        AsteroidCracked cracked = new AsteroidCracked(rawData("""
                {"timestamp":"2019-01-10T11:20:30Z","event":"AsteroidCracked","Body":"Borann A 2"}
                """.strip()));

        String presentation = verifiedText(cracked.llmPresentation());

        assertTrue(presentation.contains(
                "broke open a motherlode asteroid for mining"
        ));
        assertTrue(presentation.contains("Borann A 2"));
        assertTrue(presentation.contains(
                "does not identify the asteroid's contents"
        ));
        assertFalse(presentation.contains("valuable"));
    }

    @Test
    void communityGoalJoinReportsSignupWithoutPredictingOutcome() {
        CommunityGoalJoin join = new CommunityGoalJoin(rawData("""
                {"timestamp":"2017-08-14T13:20:28Z","event":"CommunityGoalJoin","CGID":726,"Name":"Alliance Research Initiative - Trade","System":"Kaushpoos"}
                """.strip()));

        String presentation = verifiedText(join.llmPresentation());

        assertTrue(presentation.contains(
                "signed up for"
        ));
        assertTrue(presentation.contains(
                "Alliance Research Initiative - Trade"
        ));
        assertTrue(presentation.contains("community-goal ID 726"));
        assertTrue(presentation.contains("Kaushpoos"));
        assertFalse(presentation.contains("will succeed"));
        assertFalse(presentation.contains("reward"));
    }

    @Test
    void communityGoalRewardReportsReceivedCredits() {
        CommunityGoalReward reward = new CommunityGoalReward(rawData("""
                {"timestamp":"2017-08-18T13:20:28Z","event":"CommunityGoalReward","CGID":726,"Name":"Alliance Research Initiative - Trade","System":"Kaushpoos","Reward":200000}
                """.strip()));

        String presentation = verifiedText(reward.llmPresentation());

        assertTrue(presentation.contains("received the reward"));
        assertTrue(presentation.contains(
                "Alliance Research Initiative - Trade"
        ));
        assertTrue(presentation.contains("200,000 credits"));
        assertTrue(presentation.contains("Kaushpoos"));
        assertFalse(presentation.contains("expected reward"));
    }

    @Test
    void missionAbandonedReportsFineWithoutCallingItFailure() {
        MissionAbandoned abandoned = new MissionAbandoned(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"MissionAbandoned","Name":"Mission_Collect_name","Name_Localised":"Collect industrial materials","MissionID":65343025,"Fine":2500}
                """.strip()));

        String presentation = verifiedText(abandoned.llmPresentation());

        assertTrue(presentation.contains("abandoned"));
        assertTrue(presentation.contains("Collect industrial materials"));
        assertTrue(presentation.contains("mission ID 65343025"));
        assertTrue(presentation.contains("fine of 2,500 credits"));
        assertFalse(presentation.contains("failed state"));
    }

    @Test
    void missionAcceptedExplainsAssignmentAndExpectedTerms() {
        MissionAccepted accepted = new MissionAccepted(rawData("""
                {"timestamp":"2018-02-28T12:06:37Z","event":"MissionAccepted","Faction":"Official i Bootis Liberty Party","Name":"Mission_DeliveryWing_Agriculture","LocalisedName":"Agricultural supply run: 2280 units of Tea","Commodity":"$Tea_Name;","Commodity_Localised":"Tea","Count":2280,"TargetFaction":"Ovid Vision & Co","DestinationSystem":"Ovid","DestinationStation":"Shriver Platform","Expiry":"2018-03-01T12:05:53Z","Wing":true,"Reward":2686155,"MissionID":65393626}
                """.strip()));

        String presentation = verifiedText(accepted.llmPresentation());

        assertTrue(presentation.contains("accepted and started"));
        assertTrue(presentation.contains(
                "Agricultural supply run: 2280 units of Tea"
        ));
        assertTrue(presentation.contains("offering faction"));
        assertTrue(presentation.contains("destination system"));
        assertTrue(presentation.contains("commodity"));
        assertTrue(presentation.contains("required count 2,280"));
        assertTrue(presentation.contains(
                "expected cash reward 2,686,155 credits"
        ));
        assertTrue(presentation.contains("a wing mission"));
        assertFalse(presentation.contains("completed"));
    }

    @Test
    void missionCompletedReportsActualOutcomeAndItemRewards() {
        MissionCompleted completed = new MissionCompleted(rawData("""
                {"timestamp":"2018-12-19T21:41:09Z","event":"MissionCompleted","Faction":"Inara Nexus","Name":"Mission_Courier_Elections_name","Name_Localised":"Election courier delivery","MissionID":442511682,"DestinationSystem":"Tougeir","DestinationStation":"Janes Dock","Reward":10000,"CommodityReward":[{"Name":"gold","Name_Localised":"Gold","Count":2}],"MaterialsReward":[{"Name":"DisruptedWakeEchoes","Name_Localised":"Atypical Disrupted Wake Echoes","Category":"$MICRORESOURCE_CATEGORY_Encoded;","Category_Localised":"Encoded","Count":4}],"PermitsAwarded":["Alioth"]}
                """.strip()));

        String presentation = verifiedText(completed.llmPresentation());

        assertTrue(presentation.contains("completed"));
        assertTrue(presentation.contains("Election courier delivery"));
        assertTrue(presentation.contains("cash reward 10,000 credits"));
        assertTrue(presentation.contains("Gold"));
        assertTrue(presentation.contains("x2"));
        assertTrue(presentation.contains(
                "Atypical Disrupted Wake Echoes"
        ));
        assertTrue(presentation.contains("Encoded"));
        assertTrue(presentation.contains("Alioth"));
    }

    @Test
    void missionFailedDoesNotInventFailureCause() {
        MissionFailed failed = new MissionFailed(rawData("""
                {"timestamp":"2023-01-01T10:00:00Z","event":"MissionFailed","Name":"Mission_Delivery_name","Name_Localised":"Deliver medical supplies","MissionID":99221,"Fine":5000}
                """.strip()));

        String presentation = verifiedText(failed.llmPresentation());

        assertTrue(presentation.contains("entered the failed state"));
        assertTrue(presentation.contains("Deliver medical supplies"));
        assertTrue(presentation.contains("fine of 5,000 credits"));
        assertTrue(presentation.contains(
                "does not state why the mission failed"
        ));
        assertFalse(presentation.contains("because"));
    }

    @Test
    void missionRedirectedShowsOldAndNewDestination() {
        MissionRedirected redirected = new MissionRedirected(rawData("""
                {"timestamp":"2017-08-01T09:04:07Z","event":"MissionRedirected","MissionID":65367315,"Name":"Mission_Courier_name","Name_Localised":"Courier assignment","NewDestinationStation":"Metcalf Orbital","OldDestinationStation":"Cuffey Orbital","NewDestinationSystem":"Cemiess","OldDestinationSystem":"Vequess"}
                """.strip()));

        String presentation = verifiedText(redirected.llmPresentation());

        assertTrue(presentation.contains("was redirected"));
        assertTrue(presentation.contains("Vequess"));
        assertTrue(presentation.contains("Cuffey Orbital"));
        assertTrue(presentation.contains("Cemiess"));
        assertTrue(presentation.contains("Metcalf Orbital"));
        assertTrue(presentation.contains("mission ID is 65367315"));
        assertFalse(presentation.contains("failed"));
    }

    @Test
    void holoscreenHackReportsDocumentedOwnershipTransition() {
        HoloscreenHacked hacked = new HoloscreenHacked(rawData("""
                {"timestamp":"2025-01-01T12:00:00Z","event":"HoloscreenHacked","PowerBefore":"Aisling Duval","PowerAfter":"Yuri Grom"}
                """.strip()));

        String presentation = verifiedText(hacked.llmPresentation());

        assertTrue(presentation.contains("hacked a holo-screen"));
        assertTrue(presentation.contains("Aisling Duval"));
        assertTrue(presentation.contains("Yuri Grom"));
        assertTrue(presentation.contains("recorded Power owner"));
        assertFalse(presentation.contains("merits"));
        assertFalse(presentation.contains("important"));
    }

    @Test
    void upgradeSuitReportsNewClassCostAndConsumedResources() {
        UpgradeSuit upgrade = new UpgradeSuit(rawData("""
                {"timestamp":"2022-08-19T16:41:33Z","event":"UpgradeSuit","Name":"utilitysuit_class1","Name_Localised":"Maverick Suit","SuitID":1702914472756487,"Class":2,"Cost":600000,"Resources":[{"Name":"suitschematic","Name_Localised":"Suit Schematic","Count":1},{"Name":"graphene","Name_Localised":"Graphene","Count":5}]}
                """.strip()));

        String presentation = verifiedText(upgrade.llmPresentation());

        assertTrue(presentation.contains("upgraded flight suit"));
        assertTrue(presentation.contains("Maverick Suit"));
        assertTrue(presentation.contains("new class 2"));
        assertTrue(presentation.contains("cost 600,000 credits"));
        assertTrue(presentation.contains("Suit Schematic"));
        assertTrue(presentation.contains("Graphene"));
        assertTrue(presentation.contains("x5"));
    }

    @Test
    void upgradeWeaponReportsNewClassCostAndConsumedResources() {
        UpgradeWeapon upgrade = new UpgradeWeapon(rawData("""
                {"timestamp":"2022-08-19T16:58:18Z","event":"UpgradeWeapon","Name":"wpn_m_assaultrifle_laser_fauto","Name_Localised":"TK Aphelion","Class":2,"SuitModuleID":1681611765701131,"Cost":2500000,"Resources":[{"Name":"weaponschematic","Name_Localised":"Weapon Schematic","Count":1},{"Name":"opticalfibre","Name_Localised":"Optical Fibre","Count":5}]}
                """.strip()));

        String presentation = verifiedText(upgrade.llmPresentation());

        assertTrue(presentation.contains("upgraded hand weapon"));
        assertTrue(presentation.contains("TK Aphelion"));
        assertTrue(presentation.contains("new class 2"));
        assertTrue(presentation.contains("cost 2,500,000 credits"));
        assertTrue(presentation.contains("Weapon Schematic"));
        assertTrue(presentation.contains("Optical Fibre"));
    }

    @Test
    void powerplayDefectDistinguishesChangingPowersFromLeaving() {
        PowerplayDefect defect = new PowerplayDefect(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"PowerplayDefect","FromPower":"Zachary Hudson","ToPower":"Li Yong-Rui"}
                """.strip()));

        String presentation = verifiedText(defect.llmPresentation());

        assertTrue(presentation.contains("defected in Powerplay"));
        assertTrue(presentation.contains("Zachary Hudson"));
        assertTrue(presentation.contains("Li Yong-Rui"));
        assertFalse(presentation.contains("left Powerplay entirely"));
    }

    @Test
    void powerplayJoinReportsNewPledge() {
        PowerplayJoin join = new PowerplayJoin(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"PowerplayJoin","Power":"Zachary Hudson"}
                """.strip()));

        String presentation = verifiedText(join.llmPresentation());

        assertTrue(presentation.contains("joined Powerplay power"));
        assertTrue(presentation.contains("Zachary Hudson"));
        assertFalse(presentation.contains("defected"));
    }

    @Test
    void powerplayLeaveDoesNotInventDestinationPower() {
        PowerplayLeave leave = new PowerplayLeave(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"PowerplayLeave","Power":"Li Yong-Rui"}
                """.strip()));

        String presentation = verifiedText(leave.llmPresentation());

        assertTrue(presentation.contains("left Powerplay power"));
        assertTrue(presentation.contains("Li Yong-Rui"));
        assertTrue(presentation.contains(
                "not a recorded defection to another power"
        ));
    }

    @Test
    void powerplayRankKeepsUndocumentedRankScaleNumeric() {
        PowerplayRank rank = new PowerplayRank(rawData("""
                {"timestamp":"2025-01-01T12:00:00Z","event":"PowerplayRank","Power":"Aisling Duval","Rank":12}
                """.strip()));

        String presentation = verifiedText(rank.llmPresentation());

        assertTrue(presentation.contains("Powerplay rank"));
        assertTrue(presentation.contains("Aisling Duval"));
        assertTrue(presentation.contains("source rank 12"));
        assertFalse(presentation.contains("Elite"));
        assertFalse(presentation.contains("important"));
    }

    @Test
    void newCommanderReportsNameAndPackageButOmitsAccountId() {
        NewCommander commander = new NewCommander(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"NewCommander","Name":"HRC1","FID":"F44396","Package":"ImperialBountyHunter"}
                """.strip()));

        String presentation = verifiedText(commander.llmPresentation());

        assertTrue(presentation.contains("new commander was created"));
        assertTrue(presentation.contains("HRC1"));
        assertTrue(presentation.contains(
                "starter package"
        ));
        assertTrue(presentation.contains("ImperialBountyHunter"));
        assertFalse(presentation.contains("F44396"));
    }

    @Test
    void promotionUsesDocumentedRankNamesAndKeepsUnknownExtensionsNumeric() {
        Promotion promotion = new Promotion(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"Promotion","Explore":2,"Trade":10}
                """.strip()));

        String presentation = verifiedText(promotion.llmPresentation());

        assertTrue(presentation.contains("rank promotions"));
        assertTrue(presentation.contains("exploration rank"));
        assertTrue(presentation.contains("Scout"));
        assertTrue(presentation.contains("trade rank source rank 10"));
        assertFalse(presentation.contains("Elite II"));
    }

    @Test
    void fighterDestroyedDoesNotInventPilotOrCause() {
        FighterDestroyed destroyed = new FighterDestroyed(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"FighterDestroyed"}
                """.strip()));

        String presentation = verifiedText(destroyed.llmPresentation());

        assertTrue(presentation.contains(
                "ship-launched fighter was destroyed"
        ));
        assertTrue(presentation.contains(
                "does not identify its pilot, attacker, or cause"
        ));
    }

    @Test
    void dockSrvReportsVehicleTypeAndIdentity() {
        DockSRV docked = new DockSRV(rawData("""
                {"timestamp":"2022-01-01T10:00:00Z","event":"DockSRV","ID":7,"SRVType":"combat_multicrew_srv_01","SRVType_Localised":"Scarab"}
                """.strip()));

        String presentation = verifiedText(docked.llmPresentation());

        assertTrue(presentation.contains("docked an SRV with the ship"));
        assertTrue(presentation.contains("SRV type"));
        assertTrue(presentation.contains("Scarab"));
        assertTrue(presentation.contains("vehicle ID 7"));
    }

    @Test
    void launchFighterReportsControlStateAtLaunch() {
        LaunchFighter launched = new LaunchFighter(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"LaunchFighter","Loadout":"starter","ID":2,"PlayerControlled":true}
                """.strip()));

        String presentation = verifiedText(launched.llmPresentation());

        assertTrue(presentation.contains(
                "ship-launched fighter was launched"
        ));
        assertTrue(presentation.contains("loadout"));
        assertTrue(presentation.contains("starter"));
        assertTrue(presentation.contains("fighter ID 2"));
        assertTrue(presentation.contains(
                "player-controlled from launch"
        ));
    }

    @Test
    void rebootRepairListsOnlyReportedRepairedModules() {
        RebootRepair repair = new RebootRepair(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"RebootRepair","Modules":["MainEngines","TinyHardpoint1"]}
                """.strip()));

        String presentation = verifiedText(repair.llmPresentation());

        assertTrue(presentation.contains("reboot-and-repair function"));
        assertTrue(presentation.contains("MainEngines"));
        assertTrue(presentation.contains("TinyHardpoint1"));
        assertFalse(presentation.contains("fully repaired"));
    }

    @Test
    void sellShipOnRebuyExplainsInsuranceScreenFundraising() {
        SellShipOnRebuy sale = new SellShipOnRebuy(rawData("""
                {"timestamp":"2017-07-20T08:56:39Z","event":"SellShipOnRebuy","ShipType":"Dolphin","System":"Shinrarta Dezhra","SellShipId":4,"ShipPrice":4110183}
                """.strip()));

        String presentation = verifiedText(sale.llmPresentation());

        assertTrue(presentation.contains("insurance rebuy screen"));
        assertTrue(presentation.contains("sold stored ship"));
        assertTrue(presentation.contains("Dolphin"));
        assertTrue(presentation.contains("Shinrarta Dezhra"));
        assertTrue(presentation.contains(
                "sale price 4,110,183 credits"
        ));
        assertTrue(presentation.contains("stored ship ID 4"));
    }

    @Test
    void setUserShipNameReportsExplicitNameAndShipIdentity() {
        SetUserShipName naming = new SetUserShipName(rawData("""
                {"timestamp":"2017-04-22T08:32:10Z","event":"SetUserShipName","Ship":"CobraMkIII","ShipID":7,"UserShipName":"Kairon","UserShipId":"KR-07"}
                """.strip()));

        String presentation = verifiedText(naming.llmPresentation());

        assertTrue(presentation.contains(
                "assigned the ship name"
        ));
        assertTrue(presentation.contains("Kairon"));
        assertTrue(presentation.contains("CobraMkIII"));
        assertTrue(presentation.contains("ship ID 7"));
        assertTrue(presentation.contains("KR-07"));
    }

    @Test
    void shipRedeemedReportsRedemptionWithoutInventingPurchase() {
        ShipRedeemed redeemed = new ShipRedeemed(rawData("""
                {"timestamp":"2025-01-01T12:00:00Z","event":"ShipRedeemed","ShipType":"python_nx","ShipType_Localised":"Python Mk II","NewShipID":34}
                """.strip()));

        String presentation = verifiedText(redeemed.llmPresentation());

        assertTrue(presentation.contains("new ship was redeemed"));
        assertTrue(presentation.contains("Python Mk II"));
        assertTrue(presentation.contains("new ship ID 34"));
        assertFalse(presentation.contains("bought"));
        assertFalse(presentation.contains("credits"));
    }

    @Test
    void shipyardBuyReportsPurchaseAndPreviousShipDisposition() {
        ShipyardBuy purchase = new ShipyardBuy(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"ShipyardBuy","ShipType":"FerDeLance","ShipType_Localised":"Fer-de-Lance","ShipPrice":51567040,"StoreOldShip":"CobraMkIII","StoreShipID":7,"MarketID":128666762}
                """.strip()));

        String presentation = verifiedText(purchase.llmPresentation());

        assertTrue(presentation.contains("bought a new ship"));
        assertTrue(presentation.contains("Fer-de-Lance"));
        assertTrue(presentation.contains(
                "purchase price 51,567,040 credits"
        ));
        assertTrue(presentation.contains("shipyard market ID 128666762"));
        assertTrue(presentation.contains(
                "previous ship “CobraMkIII” was stored"
        ));
        assertTrue(presentation.contains("stored ship ID 7"));
    }

    @Test
    void shipyardNewConfirmsRegisteredShipIdentityAfterPurchase() {
        ShipyardNew acquired = new ShipyardNew(rawData("""
                {"timestamp":"2016-06-10T14:32:04Z","event":"ShipyardNew","ShipType":"FerDeLance","ShipType_Localised":"Fer-de-Lance","NewShipID":12}
                """.strip()));

        String presentation = verifiedText(acquired.llmPresentation());

        assertTrue(presentation.contains("Following a ship purchase"));
        assertTrue(presentation.contains("newly acquired ship"));
        assertTrue(presentation.contains("Fer-de-Lance"));
        assertTrue(presentation.contains("new ship ID 12"));
    }

    @Test
    void shipyardSellReportsStoredShipSaleAndRemoteLocation() {
        ShipyardSell sale = new ShipyardSell(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"ShipyardSell","ShipType":"SideWinder","ShipType_Localised":"Sidewinder","SellShipID":3,"ShipPrice":28860,"MarketID":128666762,"System":"Eranin","ShipMarketID":128672276}
                """.strip()));

        String presentation = verifiedText(sale.llmPresentation());

        assertTrue(presentation.contains("sold stored ship"));
        assertTrue(presentation.contains("Sidewinder"));
        assertTrue(presentation.contains("stored ship ID 3"));
        assertTrue(presentation.contains("sale price 28,860 credits"));
        assertTrue(presentation.contains("sale market ID 128666762"));
        assertTrue(presentation.contains("system “Eranin”"));
        assertTrue(presentation.contains(
                "stored-ship market ID 128672276"
        ));
    }

    @Test
    void shipyardSwapReportsSelectedAndStoredShipsSeparately() {
        ShipyardSwap swap = new ShipyardSwap(rawData("""
                {"timestamp":"2016-07-21T14:36:06Z","event":"ShipyardSwap","ShipType":"sidewinder","ShipType_Localised":"Sidewinder","ShipID":10,"StoreOldShip":"Asp","StoreShipID":2,"MarketID":128666762}
                """.strip()));

        String presentation = verifiedText(swap.llmPresentation());

        assertTrue(presentation.contains(
                "switched to stored ship “Sidewinder”"
        ));
        assertTrue(presentation.contains("selected ship ID 10"));
        assertTrue(presentation.contains("shipyard market ID 128666762"));
        assertTrue(presentation.contains(
                "previous ship “Asp” was stored"
        ));
        assertTrue(presentation.contains("stored ship ID 2"));
    }

    @Test
    void shipyardTransferReportsRequestOriginCostAndDuration() {
        ShipyardTransfer transfer = new ShipyardTransfer(rawData("""
                {"timestamp":"2016-07-21T15:19:49Z","event":"ShipyardTransfer","ShipType":"SideWinder","ShipType_Localised":"Sidewinder","ShipID":7,"System":"Eranin","ShipMarketID":128672276,"Distance":85.639145,"TransferPrice":580,"TransferTime":900,"MarketID":128666762}
                """.strip()));

        String presentation = verifiedText(transfer.llmPresentation());

        assertTrue(presentation.contains("requested transport"));
        assertTrue(presentation.contains("Sidewinder"));
        assertTrue(presentation.contains("ship ID 7"));
        assertTrue(presentation.contains("origin system “Eranin”"));
        assertTrue(presentation.contains(
                "transfer distance 85.639145 light-years"
        ));
        assertTrue(presentation.contains("transfer cost 580 credits"));
        assertTrue(presentation.contains("transfer time 900 seconds"));
        assertTrue(presentation.contains(
                "destination market ID 128666762"
        ));
    }

    @Test
    void srvDestroyedReportsVehicleWithoutInventingCause() {
        SRVDestroyed destroyed = new SRVDestroyed(rawData("""
                {"timestamp":"2022-01-01T10:00:00Z","event":"SRVDestroyed","ID":7,"SRVType":"combat_multicrew_srv_01","SRVType_Localised":"Scorpion"}
                """.strip()));

        String presentation = verifiedText(destroyed.llmPresentation());

        assertTrue(presentation.contains(
                "surface reconnaissance vehicle (SRV) was destroyed"
        ));
        assertTrue(presentation.contains("SRV type “Scorpion”"));
        assertTrue(presentation.contains("vehicle ID 7"));
        assertTrue(presentation.contains(
                "does not identify the cause"
        ));
    }

    @Test
    void crewFireReportsDismissalNotCasualty() {
        CrewFire firing = new CrewFire(rawData("""
                {"timestamp":"2016-08-09T08:46:11Z","event":"CrewFire","Name":"Whitney Pruitt-Munoz","CrewID":42}
                """.strip()));

        String presentation = verifiedText(firing.llmPresentation());

        assertTrue(presentation.contains("dismissed crew member"));
        assertTrue(presentation.contains("Whitney Pruitt-Munoz"));
        assertTrue(presentation.contains("crew ID 42"));
        assertFalse(presentation.contains("killed"));
    }

    @Test
    void crewHireTranslatesDocumentedCombatRankAndReportsCost() {
        CrewHire hiring = new CrewHire(rawData("""
                {"timestamp":"2016-08-09T08:46:29Z","event":"CrewHire","Name":"Margaret Parrish","CrewID":17,"Faction":"The Dark Wheel","Cost":15000,"CombatRank":1}
                """.strip()));

        String presentation = verifiedText(hiring.llmPresentation());

        assertTrue(presentation.contains("hired crew member"));
        assertTrue(presentation.contains("Margaret Parrish"));
        assertTrue(presentation.contains("crew ID 17"));
        assertTrue(presentation.contains("The Dark Wheel"));
        assertTrue(presentation.contains("hiring cost 15,000 credits"));
        assertTrue(presentation.contains(
                "combat rank “Mostly Harmless”"
        ));
    }

    @Test
    void crewMemberJoinsIdentifiesPlayerMulticrewAndConnectionMode() {
        CrewMemberJoins joins = new CrewMemberJoins(rawData("""
                {"timestamp":"2022-04-01T12:00:00Z","event":"CrewMemberJoins","Crew":"Alice","Telepresence":true}
                """.strip()));

        String presentation = verifiedText(joins.llmPresentation());

        assertTrue(presentation.contains("Commander “Alice”"));
        assertTrue(presentation.contains(
                "joined the player's ship as a multicrew member"
        ));
        assertTrue(presentation.contains("marks"));
        assertTrue(presentation.contains("telepresence"));
        assertFalse(presentation.contains("NPC"));
    }

    @Test
    void crewMemberQuitsReportsDepartureFromMulticrewSession() {
        CrewMemberQuits quits = new CrewMemberQuits(rawData("""
                {"timestamp":"2022-04-01T12:05:00Z","event":"CrewMemberQuits","Crew":"Bob","Telepresence":false}
                """.strip()));

        String presentation = verifiedText(quits.llmPresentation());

        assertTrue(presentation.contains("Commander “Bob”"));
        assertTrue(presentation.contains(
                "left the player's ship multicrew session"
        ));
        assertTrue(presentation.contains("non-telepresence"));
    }

    @Test
    void joinedSquadronReportsMembershipAndIdentity() {
        JoinedSquadron joined = new JoinedSquadron(rawData("""
                {"timestamp":"2018-10-17T16:17:55Z","event":"JoinedSquadron","SquadronID":3,"SquadronName":"TestSquadron"}
                """.strip()));

        String presentation = verifiedText(joined.llmPresentation());

        assertTrue(presentation.contains(
                "joined squadron “TestSquadron”"
        ));
        assertTrue(presentation.contains("squadron ID 3"));
    }

    @Test
    void kickedFromSquadronDistinguishesRemovalFromLeaving() {
        KickedFromSquadron kicked = new KickedFromSquadron(rawData("""
                {"timestamp":"2018-10-17T16:17:55Z","event":"KickedFromSquadron","SquadronID":4,"SquadronName":"TestSquadron"}
                """.strip()));

        String presentation = verifiedText(kicked.llmPresentation());

        assertTrue(presentation.contains(
                "was removed from squadron “TestSquadron”"
        ));
        assertTrue(presentation.contains("squadron ID 4"));
        assertFalse(presentation.contains("chose to leave"));
    }

    @Test
    void leftSquadronReportsJournalFactWithoutInventingReason() {
        LeftSquadron left = new LeftSquadron(rawData("""
                {"timestamp":"2018-10-17T16:17:55Z","event":"LeftSquadron","SquadronID":3,"SquadronName":"TestSquadron"}
                """.strip()));

        String presentation = verifiedText(left.llmPresentation());

        assertTrue(presentation.contains(
                "left squadron “TestSquadron”"
        ));
        assertTrue(presentation.contains("squadron ID 3"));
        assertFalse(presentation.contains("because"));
    }

    @Test
    void npcCrewRankTranslatesDocumentedCombatRank() {
        NpcCrewRank rank = new NpcCrewRank(rawData("""
                {"timestamp":"2019-01-01T12:00:00Z","event":"NpcCrewRank","NpcCrewName":"Edmundo Frash","NpcCrewId":218204548,"RankCombat":5}
                """.strip()));

        String presentation = verifiedText(rank.llmPresentation());

        assertTrue(presentation.contains("NPC crew member"));
        assertTrue(presentation.contains("Edmundo Frash"));
        assertTrue(presentation.contains("gained a combat rank"));
        assertTrue(presentation.contains("NPC crew ID 218204548"));
        assertTrue(presentation.contains(
                "new combat rank “Master”"
        ));
    }

    @Test
    void squadronCreatedReportsNameAndIdentity() {
        SquadronCreated created = new SquadronCreated(rawData("""
                {"timestamp":"2018-10-17T16:17:55Z","event":"SquadronCreated","SquadronID":3,"SquadronName":"TestSquadron"}
                """.strip()));

        String presentation = verifiedText(created.llmPresentation());

        assertTrue(presentation.contains(
                "created squadron “TestSquadron”"
        ));
        assertTrue(presentation.contains("squadron ID 3"));
    }

    @Test
    void squadronDemotionUsesLocalizedNamesWithoutInterpretingRankNumbers() {
        SquadronDemotion demotion = new SquadronDemotion(rawData("""
                {"timestamp":"2018-10-17T16:17:55Z","event":"SquadronDemotion","SquadronID":3,"SquadronName":"TestSquadron","OldRank":2,"NewRank":3,"OldRankName":"$Squadron_DefaultRankName_Rank4;","OldRankName_Localised":"Rookie","NewRankName":"$Squadron_DefaultRankName_Rank1;","NewRankName_Localised":"Senior Officer"}
                """.strip()));

        String presentation = verifiedText(demotion.llmPresentation());

        assertTrue(presentation.contains("was demoted"));
        assertTrue(presentation.contains("TestSquadron"));
        assertTrue(presentation.contains("previous rank name “Rookie”"));
        assertTrue(presentation.contains("previous source rank 2"));
        assertTrue(presentation.contains(
                "new rank name “Senior Officer”"
        ));
        assertTrue(presentation.contains("new source rank 3"));
    }

    @Test
    void squadronPromotionUsesLocalizedNamesAndSourceRanks() {
        SquadronPromotion promotion = new SquadronPromotion(rawData("""
                {"timestamp":"2018-10-17T16:17:55Z","event":"SquadronPromotion","SquadronID":4,"SquadronName":"Explorers","OldRank":3,"NewRank":2,"OldRankName_Localised":"Pilot","NewRankName_Localised":"Officer"}
                """.strip()));

        String presentation = verifiedText(promotion.llmPresentation());

        assertTrue(presentation.contains("was promoted"));
        assertTrue(presentation.contains("Explorers"));
        assertTrue(presentation.contains("previous rank name “Pilot”"));
        assertTrue(presentation.contains("new rank name “Officer”"));
        assertTrue(presentation.contains("squadron ID 4"));
    }

    @Test
    void receiveTextUsesLocalizedSenderAndMessageInsteadOfOpaqueSymbols() {
        ReceiveText received = new ReceiveText(rawData("""
                {"timestamp":"2025-01-01T12:00:00Z","event":"ReceiveText","From":"$ShipName_Police_Independent;","From_Localised":"System Authority Vessel","Message":"$STATION_NoFireZone_entered;","Message_Localised":"No fire zone entered.","Channel":"npc"}
                """.strip()));

        String presentation = verifiedText(received.llmPresentation());

        assertTrue(presentation.contains("text message was received"));
        assertTrue(presentation.contains("System Authority Vessel"));
        assertTrue(presentation.contains("No fire zone entered."));
        assertTrue(presentation.contains("journal channel “npc”"));
        assertFalse(presentation.contains("$ShipName"));
        assertFalse(presentation.contains("$STATION"));
    }

    @Test
    void wingJoinListsCommandersAlreadyInWing() {
        WingJoin joined = new WingJoin(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"WingJoin","Others":["HRC1","Alice"]}
                """.strip()));

        String presentation = verifiedText(joined.llmPresentation());

        assertTrue(presentation.contains("player joined a wing"));
        assertTrue(presentation.contains("other recorded members"));
        assertTrue(presentation.contains("HRC1"));
        assertTrue(presentation.contains("Alice"));
    }

    @Test
    void wingLeaveDoesNotInventMembersOrReason() {
        WingLeave left = new WingLeave(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"WingLeave"}
                """.strip()));

        String presentation = verifiedText(left.llmPresentation());

        assertTrue(presentation.contains("left their current wing"));
        assertTrue(presentation.contains(
                "does not identify the former wing members"
        ));
        assertTrue(presentation.contains("reason for leaving"));
    }

    @Test
    void marketBuyReportsCommodityQuantityAndExactCosts() {
        MarketBuy purchase = new MarketBuy(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"MarketBuy","MarketID":3226155264,"Type":"xihecompanions","Type_Localised":"Xihe Biomorphic Companions","Count":10,"BuyPrice":4666,"TotalCost":46660}
                """.strip()));

        String presentation = verifiedText(purchase.llmPresentation());

        assertTrue(presentation.contains("bought commodity"));
        assertTrue(presentation.contains("Xihe Biomorphic Companions"));
        assertTrue(presentation.contains("10 units"));
        assertTrue(presentation.contains("unit price 4,666 credits"));
        assertTrue(presentation.contains("total cost 46,660 credits"));
        assertTrue(presentation.contains("market ID 3226155264"));
    }

    @Test
    void marketSellReportsPricesAndExplicitLegalityFlags() {
        MarketSell sale = new MarketSell(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"MarketSell","MarketID":128666762,"Type":"mineraloil","Type_Localised":"Mineral Oil","Count":9,"SellPrice":72,"TotalSale":648,"AvgPricePaid":0,"StolenGoods":true,"IllegalGoods":true,"BlackMarket":true}
                """.strip()));

        String presentation = verifiedText(sale.llmPresentation());

        assertTrue(presentation.contains("sold commodity"));
        assertTrue(presentation.contains("Mineral Oil"));
        assertTrue(presentation.contains("9 units"));
        assertTrue(presentation.contains("unit sale price 72 credits"));
        assertTrue(presentation.contains("total sale value 648 credits"));
        assertTrue(presentation.contains(
                "average purchase price 0 credits"
        ));
        assertTrue(presentation.contains("goods marked as stolen"));
        assertTrue(presentation.contains("goods marked as illegal here"));
        assertTrue(presentation.contains(
                "sale made on the black market"
        ));
    }

    @Test
    void redeemVoucherReportsNetAmountBrokerAndFactionBreakdown() {
        RedeemVoucher redemption = new RedeemVoucher(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"RedeemVoucher","Type":"bounty","Amount":3000,"BrokerPercentage":25.0,"Factions":[{"Faction":"Ed's 38","Amount":1000},{"Faction":"Zac's Lads","Amount":2000}]}
                """.strip()));

        String presentation = verifiedText(redemption.llmPresentation());

        assertTrue(presentation.contains("redeemed bounty payment"));
        assertTrue(presentation.contains("net payment 3,000 credits"));
        assertTrue(presentation.contains("broker percentage 25%"));
        assertTrue(presentation.contains("Ed's 38"));
        assertTrue(presentation.contains("contributed 1,000 credits"));
        assertTrue(presentation.contains("Zac's Lads"));
        assertTrue(presentation.contains("contributed 2,000 credits"));
    }

    @Test
    void searchAndRescueReportsDeliveredItemsAndReward() {
        SearchAndRescue delivery = new SearchAndRescue(rawData("""
                {"timestamp":"2020-01-01T12:00:00Z","event":"SearchAndRescue","MarketID":128666762,"Name":"occupiedcryopod","Name_Localised":"Occupied Escape Pod","Count":6,"Reward":164376}
                """.strip()));

        String presentation = verifiedText(delivery.llmPresentation());

        assertTrue(presentation.contains(
                "delivered “Occupied Escape Pod”"
        ));
        assertTrue(presentation.contains("Search and Rescue contact"));
        assertTrue(presentation.contains("6 items delivered"));
        assertTrue(presentation.contains("reward 164,376 credits"));
        assertTrue(presentation.contains("contact market ID 128666762"));
    }

    @Test
    void disembarkExplainsSourceVesselAndLocationFlags() {
        Disembark disembark = new Disembark(rawData("""
                {"timestamp":"2020-10-12T09:09:55Z","event":"Disembark","SRV":false,"Taxi":true,"Multicrew":false,"StarSystem":"Celaeno","SystemAddress":198875014308,"Body":"Celaeno 2","BodyID":10,"OnStation":true,"OnPlanet":false,"StationName":"Shinn Enterprise","StationType":"Coriolis","MarketID":3222025216}
                """.strip()));

        String presentation = verifiedText(disembark.llmPresentation());

        assertTrue(presentation.contains("stepped out of a ship"));
        assertTrue(presentation.contains("ship was a taxi"));
        assertTrue(presentation.contains("system “Celaeno”"));
        assertTrue(presentation.contains("body “Celaeno 2”"));
        assertTrue(presentation.contains(
                "station “Shinn Enterprise”"
        ));
        assertTrue(presentation.contains("on a station"));
        assertTrue(presentation.contains("not on a planet"));
    }

    @Test
    void disembarkOwnVesselWithIdReportsPlayersOwnShipId() {
        Disembark disembark = new Disembark(rawData("""
                {"timestamp":"2020-10-12T09:09:55Z","event":"Disembark","SRV":false,"Taxi":false,"Multicrew":false,"ID":123}
                """.strip()));

        String presentation = verifiedText(disembark.llmPresentation());

        assertTrue(presentation.contains("the player's own ship ID was 123"));
        assertFalse(presentation.contains("the event reported ID"));
    }

    @Test
    void disembarkSrvWithIdReportsEventIdWithoutOwnShipAssertion() {
        Disembark disembark = new Disembark(rawData("""
                {"timestamp":"2020-10-12T09:09:55Z","event":"Disembark","SRV":true,"ID":321}
                """.strip()));

        String presentation = verifiedText(disembark.llmPresentation());

        assertTrue(presentation.contains("the event reported ID 321"));
        assertFalse(presentation.contains("the player's own ship ID was"));
        assertTrue(presentation.contains("stepped out of an SRV"));
    }

    @Test
    void disembarkTaxiWithIdReportsEventIdWithoutOwnShipAssertion() {
        Disembark disembark = new Disembark(rawData("""
                {"timestamp":"2020-10-12T09:09:55Z","event":"Disembark","Taxi":true,"ID":421}
                """.strip()));

        String presentation = verifiedText(disembark.llmPresentation());

        assertTrue(presentation.contains("the event reported ID 421"));
        assertFalse(presentation.contains("the player's own ship ID was"));
        assertTrue(presentation.contains("the ship was a taxi"));
    }

    @Test
    void disembarkMulticrewWithIdReportsEventIdWithoutOwnShipAssertion() {
        Disembark disembark = new Disembark(rawData("""
                {"timestamp":"2020-10-12T09:09:55Z","event":"Disembark","Multicrew":true,"ID":521}
                """.strip()));

        String presentation = verifiedText(disembark.llmPresentation());

        assertTrue(presentation.contains("the event reported ID 521"));
        assertFalse(presentation.contains("the player's own ship ID was"));
        assertTrue(presentation.contains("the vessel belonged to another player"));
    }

    @Test
    void disembarkWithoutIdDoesNotReportAnyIdClause() {
        Disembark disembark = new Disembark(rawData("""
                {"timestamp":"2020-10-12T09:09:55Z","event":"Disembark","SRV":false,"Taxi":false,"Multicrew":false}
                """.strip()));

        String presentation = verifiedText(disembark.llmPresentation());

        assertFalse(presentation.contains("the event reported ID"));
        assertFalse(presentation.contains("the player's own ship ID was"));
    }

    @Test
    void disembarkMultipleQualifiersWithIdUsesNeutralIdClause() {
        Disembark disembark = new Disembark(rawData("""
                {"timestamp":"2020-10-12T09:09:55Z","event":"Disembark","Taxi":true,"Multicrew":true,"ID":777}
                """.strip()));

        String presentation = verifiedText(disembark.llmPresentation());

        assertTrue(presentation.contains("the event reported ID 777"));
        assertTrue(presentation.contains("777"));
        assertFalse(presentation.contains("the player's own ship ID was"));
        assertTrue(presentation.contains("the ship was a taxi"));
        assertTrue(presentation.contains("the vessel belonged to another player"));
    }

    @Test
    void dockedReportsCompletedDockingAndExplicitConditions() {
        Docked docked = new Docked(rawData("""
                {"timestamp":"2018-03-07T12:22:25Z","event":"Docked","StationName":"Jenner Orbital","StationType":"Outpost","StarSystem":"Luhman 16","SystemAddress":22960358574928,"MarketID":3228883456,"DistFromStarLS":10.061876,"Wanted":true,"ActiveFine":true,"CockpitBreach":true,"StationState":"UnderRepairs"}
                """.strip()));

        String presentation = verifiedText(docked.llmPresentation());

        assertTrue(presentation.contains(
                "completed docking at “Jenner Orbital”"
        ));
        assertTrue(presentation.contains("station type “Outpost”"));
        assertTrue(presentation.contains("system “Luhman 16”"));
        assertTrue(presentation.contains(
                "distance from the arrival star 10.061876 light-seconds"
        ));
        assertTrue(presentation.contains(
                "cockpit was breached on landing"
        ));
        assertTrue(presentation.contains("wanted locally"));
        assertTrue(presentation.contains("active fine"));
        assertTrue(presentation.contains(
                "station state “UnderRepairs”"
        ));
    }

    @Test
    void dockingCancelledRecordsPlayerCancellationAndStation() {
        DockingCancelled cancelled = new DockingCancelled(rawData("""
                {"timestamp":"2020-01-01T12:00:00Z","event":"DockingCancelled","MarketID":128666762,"StationName":"Jameson Memorial","StationType":"Orbis"}
                """.strip()));

        String presentation = verifiedText(cancelled.llmPresentation());

        assertTrue(presentation.contains(
                "player cancelled their docking request"
        ));
        assertTrue(presentation.contains("Jameson Memorial"));
        assertTrue(presentation.contains("station type “Orbis”"));
        assertTrue(presentation.contains("market ID 128666762"));
    }

    @Test
    void dockingDeniedTranslatesOnlyDocumentedReasonCode() {
        DockingDenied denied = new DockingDenied(rawData("""
                {"timestamp":"2025-01-02T19:29:14Z","event":"DockingDenied","Reason":"Distance","MarketID":3223389440,"StationName":"Shinn Enterprise","StationType":"Outpost"}
                """.strip()));

        String presentation = verifiedText(denied.llmPresentation());

        assertTrue(presentation.contains(
                "denied the player's docking request"
        ));
        assertTrue(presentation.contains("Shinn Enterprise"));
        assertTrue(presentation.contains(
                "reported reason: the ship is too far away"
        ));
        assertTrue(presentation.contains("station type “Outpost”"));
        assertFalse(presentation.contains("hostile"));
    }

    @Test
    void dockingTimeoutReportsUnansweredRequestAndStation() {
        DockingTimeout timeout = new DockingTimeout(rawData("""
                {"timestamp":"2020-01-01T12:00:00Z","event":"DockingTimeout","MarketID":128666762,"StationName":"Jameson Memorial","StationType":"Orbis"}
                """.strip()));

        String presentation = verifiedText(timeout.llmPresentation());

        assertTrue(presentation.contains("docking request"));
        assertTrue(presentation.contains("timed out"));
        assertTrue(presentation.contains("Jameson Memorial"));
        assertTrue(presentation.contains("station type “Orbis”"));
    }

    @Test
    void dropshipDeployExplainsConflictZoneExitAndLocation() {
        DropshipDeploy deploy = new DropshipDeploy(rawData("""
                {"timestamp":"2022-12-09T14:58:16Z","event":"DropshipDeploy","StarSystem":"Kazahua","SystemAddress":2871050905001,"Body":"Kazahua 2","BodyID":2,"OnStation":false,"OnPlanet":true}
                """.strip()));

        String presentation = verifiedText(deploy.llmPresentation());

        assertTrue(presentation.contains(
                "exited a shuttle dropship at a conflict zone"
        ));
        assertTrue(presentation.contains("system “Kazahua”"));
        assertTrue(presentation.contains("body “Kazahua 2”"));
        assertTrue(presentation.contains("body ID 2"));
        assertTrue(presentation.contains("not on a station"));
        assertTrue(presentation.contains("on a planet"));
    }

    @Test
    void embarkExplainsVesselLocationAndRecordedCrew() {
        Embark embark = new Embark(rawData("""
                {"timestamp":"2020-10-12T09:06:17Z","event":"Embark","SRV":false,"Taxi":false,"Multicrew":true,"StarSystem":"Panoi","SystemAddress":6955800204002,"Body":"Panoi","BodyID":0,"OnStation":true,"OnPlanet":false,"StationName":"Bowersox Terminal","StationType":"Outpost","MarketID":3221924608,"Crew":[{"Name":"Alice","Role":"Helm"},{"Name":"Bob","Role":"FireCon"}]}
                """.strip()));

        String presentation = verifiedText(embark.llmPresentation());

        assertTrue(presentation.contains("entered a ship"));
        assertTrue(presentation.contains(
                "vessel belonged to another player"
        ));
        assertTrue(presentation.contains("system “Panoi”"));
        assertTrue(presentation.contains(
                "station “Bowersox Terminal”"
        ));
        assertTrue(presentation.contains("Alice"));
        assertTrue(presentation.contains("role “Helm”"));
        assertTrue(presentation.contains("Bob"));
        assertTrue(presentation.contains("role “FireCon”"));
    }

    @Test
    void embarkOwnVesselWithIdReportsPlayersOwnShipId() {
        Embark embark = new Embark(rawData("""
                {"timestamp":"2020-10-12T09:06:17Z","event":"Embark","SRV":false,"Taxi":false,"Multicrew":false,"ID":123}
                """.strip()));

        String presentation = verifiedText(embark.llmPresentation());

        assertTrue(presentation.contains("the player's own ship ID was 123"));
        assertFalse(presentation.contains("the event reported ID"));
    }

    @Test
    void embarkSrvWithIdReportsEventIdWithoutOwnShipAssertion() {
        Embark embark = new Embark(rawData("""
                {"timestamp":"2020-10-12T09:06:17Z","event":"Embark","SRV":true,"ID":321}
                """.strip()));

        String presentation = verifiedText(embark.llmPresentation());

        assertTrue(presentation.contains("the event reported ID 321"));
        assertFalse(presentation.contains("the player's own ship ID was"));
        assertTrue(presentation.contains("the player entered an SRV"));
    }

    @Test
    void embarkTaxiWithIdReportsEventIdWithoutOwnShipAssertion() {
        Embark embark = new Embark(rawData("""
                {"timestamp":"2020-10-12T09:06:17Z","event":"Embark","Taxi":true,"ID":421}
                """.strip()));

        String presentation = verifiedText(embark.llmPresentation());

        assertTrue(presentation.contains("the event reported ID 421"));
        assertFalse(presentation.contains("the player's own ship ID was"));
        assertTrue(presentation.contains("the ship was a taxi"));
    }

    @Test
    void embarkMulticrewWithIdReportsEventIdWithoutOwnShipAssertion() {
        Embark embark = new Embark(rawData("""
                {"timestamp":"2020-10-12T09:06:17Z","event":"Embark","Multicrew":true,"ID":521}
                """.strip()));

        String presentation = verifiedText(embark.llmPresentation());

        assertTrue(presentation.contains("the event reported ID 521"));
        assertFalse(presentation.contains("the player's own ship ID was"));
        assertTrue(presentation.contains("vessel belonged to another player"));
    }

    @Test
    void embarkWithoutIdDoesNotReportAnyIdClause() {
        Embark embark = new Embark(rawData("""
                {"timestamp":"2020-10-12T09:06:17Z","event":"Embark","SRV":false,"Taxi":false,"Multicrew":false}
                """.strip()));

        String presentation = verifiedText(embark.llmPresentation());

        assertFalse(presentation.contains("the event reported ID"));
        assertFalse(presentation.contains("the player's own ship ID was"));
    }

    @Test
    void embarkMultipleQualifiersWithIdUsesNeutralIdClause() {
        Embark embark = new Embark(rawData("""
                {"timestamp":"2020-10-12T09:06:17Z","event":"Embark","Taxi":true,"Multicrew":true,"ID":777}
                """.strip()));

        String presentation = verifiedText(embark.llmPresentation());

        assertTrue(presentation.contains("the event reported ID 777"));
        assertFalse(presentation.contains("the player's own ship ID was"));
        assertTrue(presentation.contains("the ship was a taxi"));
        assertTrue(presentation.contains("vessel belonged to another player"));
    }

    @Test
    void fsdJumpReportsMovementFuelDestinationAndWarFacts() {
        FSDJump jump = new FSDJump(rawData("""
                {"timestamp":"2018-10-29T10:05:21Z","event":"FSDJump","StarSystem":"Eranin","SystemAddress":2832631632594,"StarPos":[-22.84375,36.53125,-1.1875],"Body":"Eranin","BodyID":0,"BodyType":"Star","JumpDist":13.334,"FuelUsed":2.1,"FuelLevel":25.630281,"BoostUsed":true,"SystemAllegiance":"Independent","SystemEconomy":"$economy_Agri;","SystemEconomy_Localised":"Agriculture","SystemSecondEconomy":"$economy_Refinery;","SystemSecondEconomy_Localised":"Refinery","SystemGovernment":"$government_Anarchy;","SystemGovernment_Localised":"Anarchy","SystemSecurity":"$GAlAXY_MAP_INFO_state_anarchy;","SystemSecurity_Localised":"Anarchy","Population":450000,"SystemFaction":{"Name":"Mob of Eranin","FactionState":"CivilLiberty"},"ThargoidWar":{"CurrentState":"Invasion","NextStateSuccess":"Recovery","NextStateFailure":"Controlled","WarProgress":0.4,"RemainingPorts":2}}
                """.strip()));

        String presentation = verifiedText(jump.llmPresentation());

        assertTrue(presentation.contains(
                "completed a hyperspace jump to star system “Eranin”"
        ));
        assertTrue(presentation.contains(
                "jump distance 13.334 light-years"
        ));
        assertTrue(presentation.contains("fuel used 2.1 tonnes"));
        assertTrue(presentation.contains(
                "fuel remaining 25.630281 tonnes"
        ));
        assertTrue(presentation.contains("an FSD boost was used"));
        assertTrue(presentation.contains("primary economy “Agriculture”"));
        assertTrue(presentation.contains(
                "controlling faction “Mob of Eranin”"
        ));
        assertTrue(presentation.contains(
                "affected by the Thargoid war"
        ));
        assertFalse(presentation.contains("rare"));
        assertFalse(presentation.contains("important"));
    }

    @Test
    void jetConeBoostExplainsChargedFsdBoostWithoutGuessingMultiplier() {
        JetConeBoost boost = new JetConeBoost(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"JetConeBoost","BoostValue":4.0}
                """.strip()));

        String presentation = verifiedText(boost.llmPresentation());

        assertTrue(presentation.contains(
                "white-dwarf or neutron-star jet cone"
        ));
        assertTrue(presentation.contains("charge an FSD jump boost"));
        assertTrue(presentation.contains(
                "reported jump-boost value 4"
        ));
        assertFalse(presentation.contains("multiplier"));
    }

    @Test
    void jetConeDamageNamesModuleWithoutInventingSeverity() {
        JetConeDamage damage = new JetConeDamage(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"JetConeDamage","Module":"int_hyperdrive_size5_class5","Module_Localised":"Frame Shift Drive"}
                """.strip()));

        String presentation = verifiedText(damage.llmPresentation());

        assertTrue(presentation.contains(
                "white-dwarf or neutron-star jet cone"
        ));
        assertTrue(presentation.contains(
                "damaged ship module “Frame Shift Drive”"
        ));
        assertTrue(presentation.contains(
                "does not report the amount of module damage"
        ));
        assertFalse(presentation.contains("critical"));
    }

    @Test
    void leaveBodyExplainsOrbitalCruiseBoundary() {
        LeaveBody leave = new LeaveBody(rawData("""
                {"timestamp":"2017-09-27T15:21:05Z","event":"LeaveBody","StarSystem":"Eranin","SystemAddress":2832631632594,"Body":"Eranin 2","BodyID":2}
                """.strip()));

        String presentation = verifiedText(leave.llmPresentation());

        assertTrue(presentation.contains(
                "flying away from planet “Eranin 2”"
        ));
        assertTrue(presentation.contains(
                "rose above the orbital-cruise altitude"
        ));
        assertTrue(presentation.contains("system “Eranin”"));
        assertTrue(presentation.contains("body ID 2"));
    }

    @Test
    void liftoffDistinguishesUnoccupiedShipDeparture() {
        Liftoff liftoff = new Liftoff(rawData("""
                {"timestamp":"2016-07-22T10:53:19Z","event":"Liftoff","PlayerControlled":false,"Taxi":false,"Multicrew":false,"StarSystem":"Eranin","SystemAddress":2832631632594,"Body":"Eranin 2","BodyID":2,"OnStation":false,"OnPlanet":true,"Latitude":63.468872,"Longitude":157.59938,"NearestDestination":"Dav's Hope"}
                """.strip()));

        String presentation = verifiedText(liftoff.llmPresentation());

        assertTrue(presentation.contains("A ship took off"));
        assertTrue(presentation.contains(
                "unoccupied ship took off while the player was in an SRV"
        ));
        assertTrue(presentation.contains("body “Eranin 2”"));
        assertTrue(presentation.contains(
                "nearest destination “Dav's Hope”"
        ));
        assertTrue(presentation.contains(
                "latitude 63.468872 degrees"
        ));
        assertTrue(presentation.contains("from a planet surface"));
    }

    @Test
    void supercruiseEntryReportsTransitionAndTravelContext() {
        SupercruiseEntry entry = new SupercruiseEntry(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"SupercruiseEntry","StarSystem":"Yuetu","SystemAddress":12345,"Taxi":true,"Multicrew":false,"Wanted":true}
                """.strip()));

        String presentation = verifiedText(entry.llmPresentation());

        assertTrue(presentation.contains(
                "entered supercruise from normal space"
        ));
        assertTrue(presentation.contains("system “Yuetu”"));
        assertTrue(presentation.contains("travelling in a taxi"));
        assertTrue(presentation.contains("wanted in this system"));
    }

    @Test
    void supercruiseExitReportsNormalSpaceDestination() {
        SupercruiseExit exit = new SupercruiseExit(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"SupercruiseExit","StarSystem":"Yuetu","SystemAddress":12345,"Body":"Yuetu B","BodyID":4,"BodyType":"Star","Taxi":false,"Multicrew":true}
                """.strip()));

        String presentation = verifiedText(exit.llmPresentation());

        assertTrue(presentation.contains(
                "left supercruise for normal space"
        ));
        assertTrue(presentation.contains("system “Yuetu”"));
        assertTrue(presentation.contains("near body “Yuetu B”"));
        assertTrue(presentation.contains("body ID 4"));
        assertTrue(presentation.contains("body type “Star”"));
        assertTrue(presentation.contains(
                "aboard another player's vessel"
        ));
    }

    @Test
    void touchdownDistinguishesRecalledUnoccupiedShipLanding() {
        Touchdown touchdown = new Touchdown(rawData("""
                {"timestamp":"2019-05-13T13:20:18Z","event":"Touchdown","PlayerControlled":false,"Taxi":false,"Multicrew":false,"StarSystem":"Eranin","SystemAddress":2832631632594,"Body":"Eranin 2","BodyID":2,"OnStation":false,"OnPlanet":true,"Latitude":10.503607,"Longitude":102.78981,"NearestDestination":"$SAA_Unknown_Signal;","NearestDestination_Localised":"Surface signal: Geological (9)"}
                """.strip()));

        String presentation = verifiedText(touchdown.llmPresentation());

        assertTrue(presentation.contains("landed on a planet surface"));
        assertTrue(presentation.contains(
                "unoccupied ship landed after being recalled"
        ));
        assertTrue(presentation.contains("player was in an SRV"));
        assertTrue(presentation.contains("body"));
        assertTrue(presentation.contains("Eranin 2"));
        assertTrue(presentation.contains(
                "Surface signal: Geological (9)"
        ));
        assertTrue(presentation.contains("latitude 10.503607 degrees"));
        assertTrue(presentation.contains("on a planet surface"));
    }

    @Test
    void undockedExplainsLandingPadDepartureAndVesselContext() {
        Undocked undocked = new Undocked(rawData("""
                {"timestamp":"2016-06-10T14:34:25Z","event":"Undocked","StationName":"Long Sight Base","StationType":"Outpost","MarketID":128666762,"Taxi":true,"Multicrew":false}
                """.strip()));

        String presentation = verifiedText(undocked.llmPresentation());

        assertTrue(presentation.contains(
                "lifted off from a landing pad"
        ));
        assertTrue(presentation.contains("Long Sight Base"));
        assertTrue(presentation.contains("station type"));
        assertTrue(presentation.contains("Outpost"));
        assertTrue(presentation.contains("market ID 128666762"));
        assertTrue(presentation.contains("vessel was a taxi"));
    }

    @Test
    void ussDropReportsSourceDescriptionAndNumericThreatWithoutJudgment() {
        USSDrop drop = new USSDrop(rawData("""
                {"timestamp":"2016-06-10T14:32:03Z","event":"USSDrop","USSType":"$USS_Type_ValuableSalvage;","USSType_Localised":"Valuable salvage","USSThreat":4}
                """.strip()));

        String presentation = verifiedText(drop.llmPresentation());

        assertTrue(presentation.contains(
                "dropped from supercruise at an unidentified signal source"
        ));
        assertTrue(presentation.contains("Valuable salvage"));
        assertTrue(presentation.contains("threat level 4"));
        assertFalse(presentation.contains("dangerous"));
        assertFalse(presentation.contains("important"));
    }

    @Test
    void fssBodySignalsExplainsFssSaaCountsWithoutAssigningMeaning() {
        FSSBodySignals signals = new FSSBodySignals(rawData("""
                {"timestamp":"2022-03-17T18:20:53Z","event":"FSSBodySignals","BodyName":"Phroi Blou EW-W d1-1056 2 a","BodyID":18,"SystemAddress":36293555558035,"Signals":[{"Type":"$SAA_SignalType_Geological;","Type_Localised":"Geological","Count":3},{"Type":"$SAA_SignalType_Biological;","Type_Localised":"Biological","Count":2}]}
                """.strip()));

        String presentation = verifiedText(signals.llmPresentation());

        assertTrue(presentation.contains("Full Spectrum System Scan"));
        assertTrue(presentation.contains(
                "Surface Area Analysis signal counts"
        ));
        assertTrue(presentation.contains(
                "Phroi Blou EW-W d1-1056 2 a"
        ));
        assertTrue(presentation.contains("body ID 18"));
        assertTrue(presentation.contains("Geological"));
        assertTrue(presentation.contains("3"));
        assertTrue(presentation.contains("Biological"));
        assertTrue(presentation.contains("2"));
        assertFalse(presentation.contains("rare"));
        assertFalse(presentation.contains("significant"));
    }

    @Test
    void fsdTargetExplicitlyDistinguishesSelectionFromCompletedJump() {
        FSDTarget target = new FSDTarget(rawData("""
                {"timestamp":"2020-04-27T08:02:52Z","event":"FSDTarget","Name":"Acihaut","SystemAddress":11665802405289,"StarClass":"M","RemainingJumpsInRoute":3}
                """.strip()));

        String presentation = verifiedText(target.llmPresentation());

        assertTrue(presentation.contains(
                "selected as the frame-shift-drive jump target"
        ));
        assertTrue(presentation.contains("Acihaut"));
        assertTrue(presentation.contains(
                "not a completed hyperspace jump"
        ));
        assertTrue(presentation.contains("target star class"));
        assertTrue(presentation.contains(
                "remaining jumps in the plotted route 3"
        ));
    }

    @Test
    void locationExplainsStateSnapshotWithoutInventingMovement() {
        Location location = new Location(rawData("""
                {"timestamp":"2026-07-24T16:48:49Z","event":"Location","StarSystem":"Panoi","SystemAddress":6955800204002,"StarPos":[25.09375,-80.90625,17.6875],"SystemAllegiance":"Independent","SystemEconomy":"$economy_Industrial;","SystemEconomy_Localised":"Industrial","SystemSecondEconomy":"$economy_Refinery;","SystemSecondEconomy_Localised":"Refinery","SystemGovernment":"$government_Democracy;","SystemGovernment_Localised":"Democracy","SystemSecurity":"$SYSTEM_SECURITY_medium;","SystemSecurity_Localised":"Medium Security","Population":2654856,"SystemFaction":{"Name":"Panoi Future"},"Body":"Panoi 2","BodyID":2,"BodyType":"Planet","DistFromStarLS":500.5,"Docked":true,"Latitude":22.613714,"Longitude":-44.13446,"StationName":"Shinn Enterprise","StationType":"CraterOutpost","MarketID":128831127,"StationFaction":{"Name":"Panoi Future"},"StationGovernment":"$government_Democracy;","StationGovernment_Localised":"Democracy","StationAllegiance":"Independent","StationEconomy":"$economy_Industrial;","StationEconomy_Localised":"Industrial","Taxi":false,"Multicrew":false,"InSRV":false,"OnFoot":true,"Wanted":true,"Powers":["Aisling Duval"],"PowerplayState":"Contested","ThargoidWar":{"CurrentState":"UnderAttack","NextStateSuccess":"Recovery","NextStateFailure":"Controlled","SuccessStateReached":false,"WarProgress":0.35,"RemainingPorts":2}}
                """.strip()));

        String presentation = verifiedText(location.llmPresentation());

        assertTrue(presentation.contains(
                "At game startup or after resurrection at a station"
        ));
        assertTrue(presentation.contains(
                "state snapshot, not evidence of a new movement"
        ));
        assertTrue(presentation.contains("Panoi"));
        assertTrue(presentation.contains("body ID 2"));
        assertTrue(presentation.contains(
                "distance from the system's main star 500.5 light-seconds"
        ));
        assertTrue(presentation.contains("the player was docked"));
        assertTrue(presentation.contains("the player was on foot"));
        assertTrue(presentation.contains(
                "the player was wanted in this system"
        ));
        assertTrue(presentation.contains("Shinn Enterprise"));
        assertTrue(presentation.contains("population 2,654,856"));
        assertTrue(presentation.contains("Aisling Duval"));
        assertTrue(presentation.contains(
                "affected by the Thargoid war"
        ));
        assertFalse(presentation.contains("rare"));
        assertFalse(presentation.contains("important"));
    }

    @Test
    void commanderIdentifiesSessionPlayerWithoutExposingAccountId() {
        Commander commander = new Commander(rawData("""
                {"timestamp":"2026-07-24T16:39:51Z","event":"Commander","FID":"F12345678","Name":"TESTCMDR"}
                """.strip()));

        String presentation = verifiedText(commander.llmPresentation());

        assertTrue(presentation.contains(
                "new game-loading session started"
        ));
        assertTrue(presentation.contains("Commander"));
        assertTrue(presentation.contains("TESTCMDR"));
        assertTrue(presentation.contains(
                "before the player's inventory and ship loadout"
        ));
        assertFalse(presentation.contains("F12345678"));
        assertFalse(presentation.contains("account"));
    }

    @Test
    void friendsExplainsDocumentedStatusesWithoutInventingNewLogin() {
        Map<String, String> expectedByStatus = Map.of(
                "Requested", "pending friend request",
                "Declined", "was declined",
                "Added", "added to the player's friends list",
                "Lost", "friend relationship",
                "Offline", "currently offline",
                "Online", "currently online"
        );

        for (Map.Entry<String, String> entry
                : expectedByStatus.entrySet()) {
            Friends friends = new Friends(rawData("""
                    {"timestamp":"2026-07-24T16:39:34Z","event":"Friends","Status":"%s","Name":"KotyaGaw"}
                    """.formatted(entry.getKey()).strip()));

            String presentation = verifiedText(
                    friends.llmPresentation()
            );

            assertTrue(
                    presentation.contains(entry.getValue()),
                    entry.getKey() + ": " + presentation
            );
            assertTrue(presentation.contains("KotyaGaw"));
            if ("Online".equals(entry.getKey())) {
                assertTrue(presentation.contains(
                        "can also be emitted at game startup"
                ));
                assertTrue(presentation.contains(
                        "does not prove a new login"
                ));
            }
        }
    }

    private ScanOrganic organic(String scanType, boolean wasLogged) {
        return new ScanOrganic(rawData("""
                {"timestamp":"2026-07-24T16:53:01Z","event":"ScanOrganic","ScanType":"%s","Genus":"$Codex_Ent_Bacterial_Genus_Name;","Genus_Localised":"Bacterium","Species":"$Codex_Ent_Bacterial_10_Name;","Species_Localised":"Bacterium Bullaris","Variant":"$Codex_Ent_Bacterial_10_Yttrium_Name;","Variant_Localised":"Bacterium Bullaris - Red","WasLogged":%s,"SystemAddress":23155945939738,"Body":20}
                """.formatted(scanType, wasLogged).strip()));
    }

    private RawJournalData rawData(String rawJson) {
        ParsedJournalRecord parsed = assertInstanceOf(
                ParsedJournalRecord.class,
                parser.parse(new CompleteJournalRecord(
                        "Journal.test.log",
                        0L,
                        rawJson.getBytes(StandardCharsets.UTF_8)
                ))
        );
        return new RawJournalData(
                parsed.rawJson(),
                parsed.parsedJsonObject(),
                parsed.optionalEventType(),
                parsed.optionalJournalTimestamp()
        );
    }

    private static String verifiedText(LlmEventPresentation presentation) {
        for (String sentence : presentation.sentences()) {
            assertFalse(sentence.contains("\n"));
            assertTrue(sentence.matches(".*[.!?…]$"), sentence);
        }
        return presentation.text();
    }
}
