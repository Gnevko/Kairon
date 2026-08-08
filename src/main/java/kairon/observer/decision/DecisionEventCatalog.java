package kairon.observer.decision;

import kairon.observation.journal.JournalEventLookup;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.event.carrier.*;
import kairon.observation.journal.event.colonisation.*;
import kairon.observation.journal.event.combat.*;
import kairon.observation.journal.event.engineering.*;
import kairon.observation.journal.event.exploration.*;
import kairon.observation.journal.event.inventory.*;
import kairon.observation.journal.event.mining.*;
import kairon.observation.journal.event.mission.*;
import kairon.observation.journal.event.onfoot.*;
import kairon.observation.journal.event.powerplay.*;
import kairon.observation.journal.event.session.*;
import kairon.observation.journal.event.ship.*;
import kairon.observation.journal.event.social.*;
import kairon.observation.journal.event.trade.*;
import kairon.observation.journal.event.travel.*;
import kairon.semantics.SemanticField;
import kairon.state.FlightMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What every model-eligible journal event is called in domain terms.
 *
 * <p>One rule per catalogued type, grouped by mechanism. The table is the whole
 * of the per-event decision: everything else about turning an observation into
 * a model-facing event is shared code driven by the mechanism.</p>
 *
 * <p>Coverage is total and is meant to stay that way. A model-eligible event
 * with no rule here would reach the model under a guessed name, so
 * {@link #ruleFor} returns null and the projection refuses rather than
 * inventing one; a test asserts every type in
 * {@code LlmJournalEventSelection.TARGET_NEW_ELIGIBLE} resolves to a rule and
 * that nothing here is unreachable from that profile.</p>
 *
 * <p>Keyed by class, and a class is one kind. A record the parser dispatches to
 * several classes is catalogued once under the record when its variants share a
 * kind, and per variant when they do not — the exact match wins, so the two
 * cannot both apply.</p>
 */
public final class DecisionEventCatalog {

    private static final Map<Class<? extends JournalEventObservation>,
            DecisionEventRule> RULES = build();

    private DecisionEventCatalog() {
    }

    /**
     * The rule for a journal type, or null when the type is not catalogued.
     *
     * <p>The one extension point. It used to be two: a record whose class meant
     * more than one thing earned its rule from a predicate over its own fields,
     * because a class-keyed table could not express "one {@code Scan}, two
     * kinds". The parser now dispatches such a record to one class per domain
     * event, so the class says which kind it is and the predicate is asked once,
     * at parse time, instead of again here.</p>
     */
    public static DecisionEventRule ruleFor(
            Class<? extends JournalEventObservation> eventType
    ) {
        // A variant of a split record falls back to its record's rule, so a
        // wire event whose steps share one kind is catalogued once. Where the
        // steps are genuinely different kinds — a body reading against the
        // arrival-star milestone — each variant is registered and the exact
        // match wins.
        return JournalEventLookup.forType(
                RULES,
                Objects.requireNonNull(eventType, "eventType")
        );
    }

    /**
     * The rule for one observation, which is its class's.
     *
     * <p>The class is what the parser decided from the record's own fields, and
     * the observer's admission and the graph's episode policy read those same
     * fields through {@code BodySurveyFacts}, so the three cannot disagree about
     * what a record is: a reading admitted as the arrival-star milestone is
     * projected as the milestone and recorded as the milestone's own
     * occurrence.</p>
     */
    public static DecisionEventRule ruleFor(JournalEventObservation event) {
        Objects.requireNonNull(event, "event");
        return ruleFor(event.getClass());
    }

    public static Set<Class<? extends JournalEventObservation>> coveredTypes() {
        return RULES.keySet();
    }

    /**
     * Every rule this catalogue can hand out.
     *
     * <p>{@link #coveredTypes} answers the type question; this answers the
     * reachability question, which used to differ because one rule was earned
     * by a record rather than keyed by a class. It no longer does, and the two
     * are kept apart anyway: a property asserted of "every rule" should not
     * have to know how the table happens to be keyed today.</p>
     */
    public static List<DecisionEventRule> declaredRules() {
        return List.copyOf(RULES.values());
    }

    private static Map<Class<? extends JournalEventObservation>,
            DecisionEventRule> build() {
        Map<Class<? extends JournalEventObservation>, DecisionEventRule> rules =
                new LinkedHashMap<>();
        registerIdentity(rules);
        registerSocial(rules);
        registerTravel(rules);
        registerSurface(rules);
        registerPresence(rules);
        registerVehicle(rules);
        registerDocking(rules);
        registerExploration(rules);
        registerSampling(rules);
        registerMission(rules);
        registerCombat(rules);
        registerCommerce(rules);
        registerEngineering(rules);
        registerCarrier(rules);
        registerColonisation(rules);
        registerPowerplay(rules);
        registerShipStatus(rules);
        return Map.copyOf(rules);
    }

    private static void registerIdentity(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        // The journal emits this when a Commander takes up a session, not when
        // an identity is newly recognised.
        put(rules, Commander.class,
                DecisionEventRule.of("COMMANDER_SESSION_STARTED",
                        DecisionMechanism.IDENTITY));
        put(rules, NewCommander.class,
                DecisionEventRule.of("COMMANDER_CREATED",
                        DecisionMechanism.IDENTITY));
        put(rules, Promotion.class,
                DecisionEventRule.of("RANK_PROMOTION",
                        DecisionMechanism.IDENTITY, "newRank"));
    }

    private static void registerSocial(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        // A friend is a third party, so the default "commander" name would
        // read as the Commander themselves.
        put(rules, Friends.class,
                DecisionEventRule.of("FRIEND_STATUS",
                        DecisionMechanism.SOCIAL).named("friend"));
        put(rules, ReceiveText.class,
                DecisionEventRule.of("MESSAGE_RECEIVED",
                        DecisionMechanism.SOCIAL));
        put(rules, CrewHire.class,
                DecisionEventRule.of("CREW_HIRED",
                        DecisionMechanism.SOCIAL, "credits"));
        put(rules, CrewFire.class,
                DecisionEventRule.of("CREW_DISMISSED",
                        DecisionMechanism.SOCIAL));
        put(rules, CrewMemberJoins.class,
                DecisionEventRule.of("CREW_MEMBER_JOINED",
                        DecisionMechanism.SOCIAL));
        put(rules, CrewMemberQuits.class,
                DecisionEventRule.of("CREW_MEMBER_LEFT",
                        DecisionMechanism.SOCIAL));
        put(rules, NpcCrewRank.class,
                DecisionEventRule.of("CREW_RANK_CHANGED",
                        DecisionMechanism.SOCIAL, "combatRank"));
        put(rules, JoinedSquadron.class,
                DecisionEventRule.of("SQUADRON_JOINED",
                        DecisionMechanism.SOCIAL));
        put(rules, LeftSquadron.class,
                DecisionEventRule.of("SQUADRON_LEFT",
                        DecisionMechanism.SOCIAL));
        put(rules, KickedFromSquadron.class,
                DecisionEventRule.of("SQUADRON_EXPELLED",
                        DecisionMechanism.SOCIAL));
        put(rules, SquadronCreated.class,
                DecisionEventRule.of("SQUADRON_CREATED",
                        DecisionMechanism.SOCIAL));
        put(rules, SquadronPromotion.class,
                DecisionEventRule.of("SQUADRON_PROMOTION",
                        DecisionMechanism.SOCIAL, "newRank"));
        put(rules, SquadronDemotion.class,
                DecisionEventRule.of("SQUADRON_DEMOTION",
                        DecisionMechanism.SOCIAL, "newRank"));
        put(rules, WingJoin.class,
                DecisionEventRule.of("WING_JOINED",
                        DecisionMechanism.SOCIAL));
        put(rules, WingLeave.class,
                DecisionEventRule.of("WING_LEFT",
                        DecisionMechanism.SOCIAL));
    }

    private static void registerTravel(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        // A jump names a system and no body. The occurrence it mints carries
        // the arrival star, because that is where the ship then is — which is
        // not a body the jump is about, and counting it says nothing.
        put(rules, FSDJump.class,
                DecisionEventRule.of("SYSTEM_JUMP",
                        DecisionMechanism.TRAVEL, "distanceLy"));
        // The three events whose description names the flight mode outright, so
        // the navigation context would repeat their own sentence. A jump is not
        // among them: it leaves the ship in supercruise and never says so.
        put(rules, SupercruiseEntry.class,
                DecisionEventRule.of("SUPERCRUISE_ENTERED",
                                DecisionMechanism.TRAVEL)
                        .stating(SemanticField.FLIGHT_MODE,
                                FlightMode.SUPERCRUISE));
        put(rules, SupercruiseExit.class,
                DecisionEventRule.of("SUPERCRUISE_EXITED",
                                DecisionMechanism.BODY_TRANSIT)
                        .stating(SemanticField.FLIGHT_MODE,
                                FlightMode.NORMAL_SPACE));
        // An approach does not move the flight mode at all — it says the ship
        // is in supercruise, which is why the claim belongs to the event and
        // not to the mechanism's caused fields.
        put(rules, ApproachBody.class,
                DecisionEventRule.of("BODY_APPROACHED",
                                DecisionMechanism.BODY_TRANSIT)
                        .stating(SemanticField.FLIGHT_MODE,
                                FlightMode.SUPERCRUISE));
        put(rules, LeaveBody.class,
                DecisionEventRule.of("BODY_LEFT",
                        DecisionMechanism.BODY_TRANSIT));
        put(rules, USSDrop.class,
                DecisionEventRule.of("SIGNAL_SOURCE_ENTERED",
                        DecisionMechanism.TRAVEL, "threatLevel"));
        put(rules, JetConeBoost.class,
                DecisionEventRule.of("FSD_BOOSTED",
                        DecisionMechanism.TRAVEL, "boostMultiplier"));
        put(rules, JetConeDamage.class,
                DecisionEventRule.of("JET_CONE_DAMAGE",
                        DecisionMechanism.TRAVEL));
    }

    private static void registerSurface(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        put(rules, Touchdown.class,
                DecisionEventRule.of("TOUCHDOWN",
                                DecisionMechanism.SURFACE)
                        .stating(SemanticField.FLIGHT_MODE, FlightMode.LANDED));
        // A lift-off says the ship left the surface, not what it is doing now:
        // normal space is the inference, and inferences are not statements.
        put(rules, Liftoff.class,
                DecisionEventRule.of("LIFTOFF",
                        DecisionMechanism.SURFACE));
    }

    private static void registerPresence(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        // A presence transfer is the Commander moving between vessels, and the
        // same request says where that left them: context.commander.presence,
        // which the presence mechanism always asks for. An occupancy gap beside
        // it is a question the request has already answered, and reading
        // "UNCONFIRMED" next to ON_FOOT or SRV invites doubt about a fact that
        // is not in doubt. Claimed per event rather than per mechanism, because
        // it rests on the context slice actually answering it — the vehicle
        // events raise the same gap with nothing to answer it and keep it.
        put(rules, Embark.class,
                DecisionEventRule.of("EMBARKED",
                        DecisionMechanism.PRESENCE).settling("occupancy"));
        put(rules, Disembark.class,
                DecisionEventRule.of("DISEMBARKED",
                        DecisionMechanism.PRESENCE).settling("occupancy"));
        put(rules, DropshipDeploy.class,
                DecisionEventRule.of("DROPSHIP_DEPLOYED",
                        DecisionMechanism.PRESENCE));
    }

    private static void registerVehicle(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        // Not necessarily a fighter. Frontier has been observed emitting
        // LaunchFighter for a vehicle whose later Disembark, Embark and DockSRV
        // records prove it was a Nomad SRV, and the launch record carries no
        // SRVType, localised name or any other field that would settle it. So
        // the kind says only what is known: a vehicle went out. A later event
        // that does establish the type reports it then; nothing is guessed here
        // and nothing already sent is rewritten.
        //
        // The kind settles the action itself. Its START is the adapter noting a
        // deployment beginning rather than a stage anyone can act on, and
        // launching a vehicle says nothing about where the Commander is — which
        // the context answers directly with commander.presence.
        //
        // The object goes unnamed because the record has no vessel name in it:
        // the adapter falls back to the journal's Loadout string, so the field
        // that should say what was launched said "base".
        put(rules, LaunchFighter.class,
                DecisionEventRule.of("VEHICLE_LAUNCHED",
                        DecisionMechanism.VEHICLE).unnamed().whole());
        put(rules, FighterDestroyed.class,
                DecisionEventRule.of("FIGHTER_DESTROYED",
                        DecisionMechanism.VEHICLE));
        put(rules, DockSRV.class,
                DecisionEventRule.of("VEHICLE_RECOVERED",
                        DecisionMechanism.VEHICLE));
        put(rules, SRVDestroyed.class,
                DecisionEventRule.of("SRV_DESTROYED",
                        DecisionMechanism.VEHICLE));
    }

    private static void registerDocking(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        put(rules, Docked.class,
                DecisionEventRule.of("DOCKED", DecisionMechanism.DOCKING)
                        .stating(SemanticField.FLIGHT_MODE, FlightMode.DOCKED));
        put(rules, Undocked.class,
                DecisionEventRule.of("UNDOCKED", DecisionMechanism.DOCKING));
        put(rules, DockingDenied.class,
                DecisionEventRule.of("DOCKING_DENIED",
                        DecisionMechanism.DOCKING));
        put(rules, DockingCancelled.class,
                DecisionEventRule.of("DOCKING_CANCELLED",
                        DecisionMechanism.DOCKING));
        put(rules, DockingTimeout.class,
                DecisionEventRule.of("DOCKING_TIMED_OUT",
                        DecisionMechanism.DOCKING));
    }

    private static void registerExploration(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        put(rules, SAAScanComplete.class,
                DecisionEventRule.of("BODY_MAPPING_COMPLETED",
                        DecisionMechanism.EXPLORATION, "probesUsed"));
        // Only the detailed scan reaches here; the shallower depths establish
        // nothing and are declined before a turn opens. A repeat of the same
        // reading is never recorded, so a body-scoped count could only ever
        // say one.
        //
        // Keyed by the record rather than by the reading variant, so a scan
        // that is not the arrival-star milestone is catalogued once: the
        // milestone below is the exact match and wins, and everything else
        // reaches this rule through the record it belongs to.
        put(rules, Scan.class,
                DecisionEventRule.of("BODY_SCANNED",
                        DecisionMechanism.EXPLORATION));
        // The other thing a scan record reports, and the only record that ever
        // carries it: this star had not been discovered before now. Filing it
        // under BODY_SCANNED would report a scan the Commander did not take;
        // not filing it at all is what silently dropped the arrival star's
        // class and discovery flag. The kind claims exactly what the record
        // says — not that a first-discovery credit has been registered, which
        // the journal does not say and selling the data is what would.
        //
        // About the system, not about a body: what it says of the arrival star
        // is the whole of the turn, and that star's survey flags, its distance
        // of zero from the arrival point and a coarse type of STAR beside its
        // class made a two-sentence turn out of a one-sentence fact.
        put(rules, Scan.UndiscoveredStar.class,
                DecisionEventRule.of("SYSTEM_UNDISCOVERED_CONFIRMED",
                                DecisionMechanism.EXPLORATION)
                        .reading(DecisionContextProfile.SYSTEM_ONLY)
                        .named("arrivalStar")
                        .retaining(
                                "system",
                                "starType",
                                "previouslyDiscovered"
                        ));
        // One kind for both scanners. What the Commander learned is that this
        // body carries these signals; which instrument said so first is
        // Kairon's bookkeeping, and a second identical reading is not a second
        // finding. Both are catalogued because either can be the instrument
        // that reports a set first, and a finding nobody is told about is a
        // finding that did not reach the Commander.
        put(rules, FSSBodySignals.class,
                DecisionEventRule.of("BODY_SIGNALS_FOUND",
                        DecisionMechanism.EXPLORATION));
        // The surface scanner is the only instrument that names what it found,
        // and the whole point of firing probes is to learn which organisms are
        // down there. The system scanner counts signals and names nothing, so
        // it lists nothing.
        put(rules, SAASignalsFound.class,
                DecisionEventRule.of("BODY_SIGNALS_FOUND",
                        DecisionMechanism.EXPLORATION)
                        .namingOrganisms());
        // Scoped to the system, like a jump: whichever body happened to be
        // selected when the survey completed is not what the survey is about.
        put(rules, FSSAllBodiesFound.class,
                DecisionEventRule.of("SYSTEM_SURVEY_COMPLETED",
                        DecisionMechanism.EXPLORATION, "bodyCount")
                        );
        // Exploration, read against the system alone: a codex entry cannot say
        // which body it is about. Its BodyID is contradicted by the journal
        // that emits it — the measured replay files a Sudarsky-class gas giant
        // and a T Tauri star under body 0 of systems whose body 0 the adjacent
        // scans report as a K star and a B star — so attaching what Kairon
        // knows about the current body to it describes two objects as one. A
        // narrower slice, not a different family of game event.
        //
        // The entry stands on its name and on whether it is new. Its category
        // and region are the journal's own rubric in the client's language —
        // "Био- и геонаходки" for $Codex_Category_Biology; — and a rubric is not
        // a fact about the discovery. The region is worse than useless beside
        // isNewEntry: the game means new *for that region*, the document never
        // says so, and the measured run produced "already found in other
        // regions, and here it is the first" out of the two standing together.
        // The system it was filed in is the situation, and context.system says
        // it under the name the whole document uses.
        put(rules, CodexEntry.class,
                DecisionEventRule.of("CODEX_ENTRY_RECORDED",
                                DecisionMechanism.EXPLORATION)
                        .reading(DecisionContextProfile.SYSTEM_ONLY)
                        .named("entry")
                        .retaining("isNewEntry"));
        put(rules, SellExplorationData.class,
                DecisionEventRule.of("EXPLORATION_DATA_SOLD",
                        DecisionMechanism.COMMERCE, "credits"));
        put(rules, MultiSellExplorationData.class,
                DecisionEventRule.of("EXPLORATION_DATA_SOLD",
                        DecisionMechanism.COMMERCE, "credits"));
        put(rules, SellOrganicData.class,
                DecisionEventRule.of("ORGANIC_DATA_SOLD",
                        DecisionMechanism.COMMERCE, "credits"));
        put(rules, AsteroidCracked.class,
                DecisionEventRule.of("ASTEROID_CRACKED",
                        DecisionMechanism.EXPLORATION));
        put(rules, MaterialDiscovered.class,
                DecisionEventRule.of("MATERIAL_DISCOVERED",
                        DecisionMechanism.EXPLORATION, "discoveryNumber"));
    }

    private static void registerSampling(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        // The one genuinely multi-step mechanism in the catalogue: Log,
        // Sample and Analyse are three events of one sequence, and only the
        // last completes it.
        //
        // Those same three are three structural types in the graph, so the
        // body-scoped count of "this event type here" counts logs, or samples,
        // or analyses — never biological samples. Under the one shared kind the
        DecisionEventRule sample = DecisionEventRule.of("BIOLOGICAL_SAMPLE",
                        DecisionMechanism.SAMPLING).named("organism")
                .staged();
        put(rules, ScanOrganic.class, sample);
        // One kind, two slices. The analysis that finishes a sample is the one
        // turn where what else grows here answers a question the Commander is
        // actually in the middle of; the log and the sample before it are not,
        // and the inventory was travelling with every landing and approach of
        // the body saying "not collected" about an organism nobody had started
        // on. Registered per variant because it is the same domain event read
        // against more of the situation — the kind is unchanged.
        put(rules, ScanOrganic.Analysed.class,
                sample.reading(DecisionContextProfile.SAMPLING_ANALYSED));
    }

    private static void registerMission(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        put(rules, MissionAccepted.class,
                DecisionEventRule.of("MISSION_ACCEPTED",
                        DecisionMechanism.MISSION));
        put(rules, MissionCompleted.class,
                DecisionEventRule.of("MISSION_COMPLETED",
                        DecisionMechanism.MISSION, "credits"));
        put(rules, MissionFailed.class,
                DecisionEventRule.of("MISSION_FAILED",
                        DecisionMechanism.MISSION));
        put(rules, MissionAbandoned.class,
                DecisionEventRule.of("MISSION_ABANDONED",
                        DecisionMechanism.MISSION));
        put(rules, MissionRedirected.class,
                DecisionEventRule.of("MISSION_REDIRECTED",
                        DecisionMechanism.MISSION));
        put(rules, CommunityGoalJoin.class,
                DecisionEventRule.of("COMMUNITY_GOAL_JOINED",
                        DecisionMechanism.MISSION));
        put(rules, CommunityGoalReward.class,
                DecisionEventRule.of("COMMUNITY_GOAL_REWARD",
                        DecisionMechanism.MISSION, "credits"));
    }

    private static void registerCombat(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        put(rules, Bounty.class,
                DecisionEventRule.of("BOUNTY_AWARDED",
                        DecisionMechanism.COMBAT, "credits"));
        put(rules, CommitCrime.class,
                DecisionEventRule.of("CRIME_COMMITTED",
                        DecisionMechanism.COMBAT));
        put(rules, Died.class,
                DecisionEventRule.of("COMMANDER_DIED",
                        DecisionMechanism.COMBAT, "killerCombatRank"));
        put(rules, PVPKill.class,
                DecisionEventRule.of("PLAYER_KILLED",
                        DecisionMechanism.COMBAT).named("victim"));
        put(rules, Interdicted.class,
                DecisionEventRule.of("INTERDICTED",
                        DecisionMechanism.COMBAT));
        put(rules, Interdiction.class,
                DecisionEventRule.of("INTERDICTION_ATTEMPTED",
                        DecisionMechanism.COMBAT));
        put(rules, EscapeInterdiction.class,
                DecisionEventRule.of("INTERDICTION_ESCAPED",
                        DecisionMechanism.COMBAT));
        put(rules, UnderAttack.class,
                DecisionEventRule.of("UNDER_ATTACK",
                        DecisionMechanism.COMBAT));
        put(rules, SelfDestruct.class,
                DecisionEventRule.of("SELF_DESTRUCT",
                        DecisionMechanism.COMBAT));
    }

    private static void registerCommerce(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        put(rules, MarketBuy.class,
                DecisionEventRule.of("COMMODITY_BOUGHT",
                        DecisionMechanism.COMMERCE, "units"));
        put(rules, MarketSell.class,
                DecisionEventRule.of("COMMODITY_SOLD",
                        DecisionMechanism.COMMERCE, "units"));
        put(rules, RedeemVoucher.class,
                DecisionEventRule.of("VOUCHER_REDEEMED",
                        DecisionMechanism.COMMERCE, "credits"));
        put(rules, SearchAndRescue.class,
                DecisionEventRule.of("SALVAGE_HANDED_IN",
                        DecisionMechanism.COMMERCE, "units"));
        put(rules, CollectCargo.class,
                DecisionEventRule.of("CARGO_COLLECTED",
                        DecisionMechanism.COMMERCE, "units"));
        put(rules, EjectCargo.class,
                DecisionEventRule.of("CARGO_EJECTED",
                        DecisionMechanism.COMMERCE, "units"));
        put(rules, CargoTransfer.class,
                DecisionEventRule.of("CARGO_TRANSFERRED",
                        DecisionMechanism.COMMERCE, "units"));
        put(rules, ShipyardBuy.class,
                DecisionEventRule.of("SHIP_PURCHASED",
                        DecisionMechanism.COMMERCE, "credits"));
        put(rules, ShipyardNew.class,
                DecisionEventRule.of("SHIP_DELIVERED",
                        DecisionMechanism.COMMERCE));
        put(rules, ShipyardSell.class,
                DecisionEventRule.of("SHIP_SOLD",
                        DecisionMechanism.COMMERCE, "credits"));
        put(rules, ShipyardSwap.class,
                DecisionEventRule.of("SHIP_SWAPPED",
                        DecisionMechanism.COMMERCE));
        // A transfer starts a delivery that finishes later, so its START stage
        // and false completion are real information rather than ceremony.
        put(rules, ShipyardTransfer.class,
                DecisionEventRule.of("SHIP_TRANSFER_SCHEDULED",
                        DecisionMechanism.COMMERCE, "distanceLy").staged());
        put(rules, SellShipOnRebuy.class,
                DecisionEventRule.of("SHIP_SOLD_ON_REBUY",
                        DecisionMechanism.COMMERCE, "credits"));
        put(rules, ShipRedeemed.class,
                DecisionEventRule.of("SHIP_REDEEMED",
                        DecisionMechanism.COMMERCE));
        put(rules, SetUserShipName.class,
                DecisionEventRule.of("SHIP_RENAMED",
                        DecisionMechanism.COMMERCE));
    }

    private static void registerEngineering(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        put(rules, EngineerCraft.class,
                DecisionEventRule.of("BLUEPRINT_APPLIED",
                        DecisionMechanism.ENGINEERING, "level"));
        put(rules, EngineerContribution.class,
                DecisionEventRule.of("ENGINEER_CONTRIBUTION",
                        DecisionMechanism.ENGINEERING, "units"));
        put(rules, EngineerLegacyConvert.class,
                DecisionEventRule.of("BLUEPRINT_CONVERTED",
                        DecisionMechanism.ENGINEERING));
        put(rules, TechnologyBroker.class,
                DecisionEventRule.of("TECHNOLOGY_UNLOCKED",
                        DecisionMechanism.ENGINEERING));
        put(rules, UpgradeSuit.class,
                DecisionEventRule.of("SUIT_UPGRADED",
                        DecisionMechanism.ENGINEERING, "newClass"));
        put(rules, UpgradeWeapon.class,
                DecisionEventRule.of("WEAPON_UPGRADED",
                        DecisionMechanism.ENGINEERING, "newClass"));
        put(rules, HoloscreenHacked.class,
                DecisionEventRule.of("HOLOSCREEN_HACKED",
                        DecisionMechanism.ENGINEERING));
    }

    private static void registerCarrier(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        put(rules, CarrierBuy.class,
                DecisionEventRule.of("CARRIER_PURCHASED",
                        DecisionMechanism.CARRIER, "credits"));
        put(rules, CarrierDecommission.class,
                DecisionEventRule.of("CARRIER_DECOMMISSION_SCHEDULED",
                        DecisionMechanism.CARRIER, "credits"));
        put(rules, CarrierCancelDecommission.class,
                DecisionEventRule.of("CARRIER_DECOMMISSION_CANCELLED",
                        DecisionMechanism.CARRIER));
        // Requested now, executed later: the pending jump is the whole point.
        put(rules, CarrierJumpRequest.class,
                DecisionEventRule.of("CARRIER_JUMP_SCHEDULED",
                        DecisionMechanism.CARRIER).staged());
        put(rules, CarrierJump.class,
                DecisionEventRule.of("CARRIER_JUMPED",
                        DecisionMechanism.CARRIER));
        put(rules, CarrierJumpCancelled.class,
                DecisionEventRule.of("CARRIER_JUMP_CANCELLED",
                        DecisionMechanism.CARRIER));
        put(rules, CarrierNameChange.class,
                DecisionEventRule.of("CARRIER_RENAMED",
                        DecisionMechanism.CARRIER));
    }

    private static void registerColonisation(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        put(rules, ColonisationSystemClaim.class,
                DecisionEventRule.of("SYSTEM_CLAIMED",
                        DecisionMechanism.COLONISATION));
        put(rules, ColonisationSystemClaimRelease.class,
                DecisionEventRule.of("SYSTEM_CLAIM_RELEASED",
                        DecisionMechanism.COLONISATION));
        put(rules, ColonisationBeaconDeployed.class,
                DecisionEventRule.of("COLONISATION_BEACON_DEPLOYED",
                        DecisionMechanism.COLONISATION));
        // A depot reports progress repeatedly until it is complete.
        put(rules, ColonisationConstructionDepot.class,
                DecisionEventRule.of("CONSTRUCTION_PROGRESS",
                        DecisionMechanism.COLONISATION, "progress").staged());
        put(rules, ColonisationContribution.class,
                DecisionEventRule.of("CONSTRUCTION_CONTRIBUTION",
                        DecisionMechanism.COLONISATION, "units"));
        put(rules, CompleteConstruction.class,
                DecisionEventRule.of("CONSTRUCTION_COMPLETED",
                        DecisionMechanism.COLONISATION));
    }

    private static void registerPowerplay(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        put(rules, PowerplayJoin.class,
                DecisionEventRule.of("POWER_JOINED",
                        DecisionMechanism.POWERPLAY));
        put(rules, PowerplayLeave.class,
                DecisionEventRule.of("POWER_LEFT",
                        DecisionMechanism.POWERPLAY));
        put(rules, PowerplayDefect.class,
                DecisionEventRule.of("POWER_DEFECTED",
                        DecisionMechanism.POWERPLAY));
        put(rules, PowerplayRank.class,
                DecisionEventRule.of("POWER_RANK_CHANGED",
                        DecisionMechanism.POWERPLAY, "newRank"));
    }

    private static void registerShipStatus(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules
    ) {
        put(rules, HullDamage.class,
                DecisionEventRule.of("HULL_DAMAGE",
                        DecisionMechanism.SHIP_STATUS, "hullHealth"));
        put(rules, HeatDamage.class,
                DecisionEventRule.of("HEAT_DAMAGE",
                        DecisionMechanism.SHIP_STATUS));
        put(rules, CockpitBreached.class,
                DecisionEventRule.of("COCKPIT_BREACHED",
                        DecisionMechanism.SHIP_STATUS));
        put(rules, SystemsShutdown.class,
                DecisionEventRule.of("SYSTEMS_SHUTDOWN",
                        DecisionMechanism.SHIP_STATUS));
        put(rules, RebootRepair.class,
                DecisionEventRule.of("SHIP_REBOOT_REPAIR",
                        DecisionMechanism.SHIP_STATUS));
    }

    private static void put(
            Map<Class<? extends JournalEventObservation>,
                    DecisionEventRule> rules,
            Class<? extends JournalEventObservation> eventType,
            DecisionEventRule rule
    ) {
        if (rules.put(eventType, rule) != null) {
            throw new IllegalStateException(
                    "duplicate decision rule for " + eventType.getName()
            );
        }
    }
}
