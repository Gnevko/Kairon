package kairon.semantics;

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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What kind of thing each journal event is, before anyone asks what to do with
 * it.
 *
 * <p>Three answers, and each is a property of the record rather than a
 * judgement about it. A {@code NEW} event reports something that happened: an
 * arrival, an action, a result, a message. A {@code CONTEXT_ONLY} event reports
 * what is now true without anything having happened between two moments —
 * a session restore, a selected route. Everything else is
 * {@code DIAGNOSTIC_ONLY}: kept for the trace and the corpus, and for nothing
 * else.</p>
 *
 * <h2>Why the classification lives here</h2>
 * <p>It used to live in the observer, and {@code SemanticSourceRoles} read it
 * from there — so the semantic layer imported the observer, and the meaning of
 * a record was defined by the profile of the one consumer that happened to be
 * built first. The direction is now the other way: this is the classification,
 * and {@code LlmJournalEventSelection} is a view of it. Narrowing what the model
 * is shown — which the observer does, per record, in
 * {@code admitsAsTrigger} — cannot move a source role, and neither can renaming
 * or re-scoping a profile.</p>
 *
 * <p>One catalogue, not two. The observer keeps what is genuinely about the
 * model — the profile names, the presentation-readiness requirement, and the
 * per-record admission rules — and reads the lists from here.</p>
 *
 * <p>Nothing here decides retention. {@link ObservationSemantics#retentionOf}
 * is keyed on capture mode alone, so changing which types are {@code NEW} or
 * {@code CONTEXT_ONLY} cannot silently change which effects survive to a later
 * turn.</p>
 */
public final class SemanticSourceRoleCatalog {

    /**
     * Journal events that report something that happened.
     *
     * <p>Declaration order is the product grouping the profile was researched
     * in and is preserved: it is what a reader compares against the catalogue.
     * </p>
     */
    private static final List<Class<? extends JournalEventObservation>>
            NEW_EVENT_TYPES = List.of(
                    CarrierBuy.class,
                    CarrierCancelDecommission.class,
                    CarrierDecommission.class,
                    CarrierJump.class,
                    CarrierJumpCancelled.class,
                    CarrierJumpRequest.class,
                    CarrierNameChange.class,
                    ColonisationBeaconDeployed.class,
                    ColonisationConstructionDepot.class,
                    ColonisationContribution.class,
                    ColonisationSystemClaim.class,
                    ColonisationSystemClaimRelease.class,
                    CompleteConstruction.class,
                    Bounty.class,
                    CockpitBreached.class,
                    CommitCrime.class,
                    Died.class,
                    EscapeInterdiction.class,
                    HeatDamage.class,
                    HullDamage.class,
                    Interdicted.class,
                    Interdiction.class,
                    PVPKill.class,
                    SelfDestruct.class,
                    SystemsShutdown.class,
                    UnderAttack.class,
                    EngineerContribution.class,
                    EngineerCraft.class,
                    EngineerLegacyConvert.class,
                    TechnologyBroker.class,
                    CodexEntry.class,
                    FSSAllBodiesFound.class,
                    FSSBodySignals.class,
                    MultiSellExplorationData.class,
                    SAAScanComplete.class,
                    SAASignalsFound.class,
                    Scan.class,
                    ScanOrganic.class,
                    SellExplorationData.class,
                    SellOrganicData.class,
                    CargoTransfer.class,
                    CollectCargo.class,
                    EjectCargo.class,
                    MaterialDiscovered.class,
                    AsteroidCracked.class,
                    CommunityGoalJoin.class,
                    CommunityGoalReward.class,
                    MissionAbandoned.class,
                    MissionAccepted.class,
                    MissionCompleted.class,
                    MissionFailed.class,
                    MissionRedirected.class,
                    HoloscreenHacked.class,
                    UpgradeSuit.class,
                    UpgradeWeapon.class,
                    PowerplayDefect.class,
                    PowerplayJoin.class,
                    PowerplayLeave.class,
                    PowerplayRank.class,
                    Commander.class,
                    NewCommander.class,
                    Promotion.class,
                    FighterDestroyed.class,
                    DockSRV.class,
                    LaunchFighter.class,
                    RebootRepair.class,
                    SellShipOnRebuy.class,
                    SetUserShipName.class,
                    ShipRedeemed.class,
                    ShipyardBuy.class,
                    ShipyardNew.class,
                    ShipyardSell.class,
                    ShipyardSwap.class,
                    ShipyardTransfer.class,
                    SRVDestroyed.class,
                    CrewFire.class,
                    CrewHire.class,
                    Friends.class,
                    CrewMemberJoins.class,
                    CrewMemberQuits.class,
                    JoinedSquadron.class,
                    KickedFromSquadron.class,
                    LeftSquadron.class,
                    NpcCrewRank.class,
                    SquadronCreated.class,
                    SquadronDemotion.class,
                    SquadronPromotion.class,
                    ReceiveText.class,
                    WingJoin.class,
                    WingLeave.class,
                    MarketBuy.class,
                    MarketSell.class,
                    RedeemVoucher.class,
                    SearchAndRescue.class,
                    ApproachBody.class,
                    Disembark.class,
                    Docked.class,
                    DockingCancelled.class,
                    DockingDenied.class,
                    DockingTimeout.class,
                    DropshipDeploy.class,
                    Embark.class,
                    FSDJump.class,
                    JetConeBoost.class,
                    JetConeDamage.class,
                    LeaveBody.class,
                    Liftoff.class,
                    SupercruiseEntry.class,
                    SupercruiseExit.class,
                    Touchdown.class,
                    Undocked.class,
                    USSDrop.class
            );

    /** Journal events that establish state without reporting an event. */
    private static final List<Class<? extends JournalEventObservation>>
            CONTEXT_ONLY_EVENT_TYPES = List.of(
                    FSDTarget.class,
                    Location.class
            );

    private static final Set<Class<? extends JournalEventObservation>>
            NEW_EVENT_TYPE_SET = Set.copyOf(NEW_EVENT_TYPES);
    private static final Set<Class<? extends JournalEventObservation>>
            CONTEXT_ONLY_EVENT_TYPE_SET = Set.copyOf(CONTEXT_ONLY_EVENT_TYPES);

    static {
        if (NEW_EVENT_TYPE_SET.size() != NEW_EVENT_TYPES.size()
                || CONTEXT_ONLY_EVENT_TYPE_SET.size()
                        != CONTEXT_ONLY_EVENT_TYPES.size()) {
            throw new ExceptionInInitializerError(
                    "a journal event type is classified twice"
            );
        }
        Set<Class<? extends JournalEventObservation>> overlap =
                new HashSet<>(NEW_EVENT_TYPE_SET);
        overlap.retainAll(CONTEXT_ONLY_EVENT_TYPE_SET);
        if (!overlap.isEmpty()) {
            throw new ExceptionInInitializerError(
                    "a journal event type cannot be both new and context "
                            + "only: " + overlap
            );
        }
    }

    private SemanticSourceRoleCatalog() {
    }

    /** The role of one journal event type. Never null. */
    public static SemanticSourceRole roleOf(
            Class<? extends JournalEventObservation> eventType
    ) {
        Objects.requireNonNull(eventType, "eventType");
        if (NEW_EVENT_TYPE_SET.contains(eventType)) {
            return SemanticSourceRole.NEW;
        }
        if (CONTEXT_ONLY_EVENT_TYPE_SET.contains(eventType)) {
            return SemanticSourceRole.CONTEXT_ONLY;
        }
        return SemanticSourceRole.DIAGNOSTIC_ONLY;
    }

    /** Every journal event type that reports something that happened. */
    public static List<Class<? extends JournalEventObservation>>
            newEventTypes() {
        return NEW_EVENT_TYPES;
    }

    /** Every journal event type that only establishes standing state. */
    public static List<Class<? extends JournalEventObservation>>
            contextOnlyEventTypes() {
        return CONTEXT_ONLY_EVENT_TYPES;
    }
}
