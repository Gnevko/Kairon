package kairon.behavior.classify;

import kairon.observation.journal.JournalEventLookup;
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
import kairon.observation.journal.event.inventory.Cargo;
import kairon.observation.journal.event.inventory.MaterialCollected;
import kairon.observation.journal.event.inventory.Materials;
import kairon.observation.journal.event.mission.MissionAbandoned;
import kairon.observation.journal.event.mission.MissionAccepted;
import kairon.observation.journal.event.mission.MissionCompleted;
import kairon.observation.journal.event.mission.MissionFailed;
import kairon.observation.journal.event.mission.Missions;
import kairon.observation.journal.event.onfoot.ShipLocker;
import kairon.observation.journal.event.session.Commander;
import kairon.observation.journal.event.session.LoadGame;
import kairon.observation.journal.event.session.Music;
import kairon.observation.journal.event.session.Shutdown;
import kairon.observation.journal.event.ship.DockSRV;
import kairon.observation.journal.event.ship.LaunchDrone;
import kairon.observation.journal.event.ship.LaunchFighter;
import kairon.observation.journal.event.ship.LaunchSRV;
import kairon.observation.journal.event.ship.Loadout;
import kairon.observation.journal.event.social.Friends;
import kairon.observation.journal.event.social.ReceiveText;
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

import java.util.Objects;
import java.util.Set;

/**
 * Technical admission policy for the behavior graph.
 *
 * <p>This policy decides whether an event affects graph structure or context;
 * it does not assign importance, intent, or comment-worthiness. Types not
 * explicitly admitted are noise in the first version.</p>
 */
public final class EventSignificancePolicy {

    private static final Set<Class<? extends JournalEventObservation>>
            BOUNDARY_TYPES = Set.of(
                    FSDJump.class,
                    Location.class,
                    Shutdown.class
            );

    private static final Set<Class<? extends JournalEventObservation>>
            SIGNIFICANT_TYPES = Set.of(
                    FSSDiscoveryScan.class,
                    FSSAllBodiesFound.class,
                    // Two different facts, and both are structural. Completing
                    // a surface survey is the end of a deliberate multi-step
                    // action the Commander chose to take; the signals record
                    // that follows is what the scanner then reported. Merging
                    // them would lose the action, and dropping the completion
                    // would leave the result with nothing that caused it.
                    SAAScanComplete.class,
                    SAASignalsFound.class,
                    // A detailed body scan and a reported signal set are both
                    // results the Commander went and got. Which individual
                    // record is a distinct result rather than the same one
                    // restated is not something a type can say, and is decided
                    // per occurrence by BodySurveySelectionPolicy.
                    Scan.class,
                    FSSBodySignals.class,
                    ApproachBody.class,
                    SupercruiseEntry.class,
                    SupercruiseExit.class,
                    StartJump.class,
                    FSDTarget.class,
                    LaunchFighter.class,
                    LaunchSRV.class,
                    Touchdown.class,
                    Disembark.class,
                    ScanOrganic.class,
                    Embark.class,
                    Liftoff.class,
                    LeaveBody.class,
                    DockSRV.class,
                    DockingRequested.class,
                    DockingGranted.class,
                    Docked.class,
                    Undocked.class,
                    LaunchDrone.class,
                    Interdicted.class,
                    UnderAttack.class,
                    MaterialCollected.class,
                    FuelScoop.class,
                    MarketBuy.class,
                    MarketSell.class,
                    RedeemVoucher.class,
                    MissionAccepted.class,
                    MissionCompleted.class,
                    MissionFailed.class,
                    MissionAbandoned.class
            );

    private static final Set<Class<? extends JournalEventObservation>>
            CONTEXT_TYPES = Set.of(
                    Commander.class,
                    LoadGame.class,
                    Loadout.class,
                    Cargo.class,
                    Materials.class,
                    ShipLocker.class,
                    Missions.class
            );

    private static final Set<Class<? extends JournalEventObservation>>
            EXPLICIT_NOISE_TYPES = Set.of(
                    Music.class,
                    Friends.class,
                    ReceiveText.class
            );

    static {
        requireDisjoint(BOUNDARY_TYPES, SIGNIFICANT_TYPES);
        requireDisjoint(BOUNDARY_TYPES, CONTEXT_TYPES);
        requireDisjoint(SIGNIFICANT_TYPES, CONTEXT_TYPES);
        requireDisjoint(BOUNDARY_TYPES, EXPLICIT_NOISE_TYPES);
        requireDisjoint(SIGNIFICANT_TYPES, EXPLICIT_NOISE_TYPES);
        requireDisjoint(CONTEXT_TYPES, EXPLICIT_NOISE_TYPES);
    }

    public EventSignificance classify(JournalEventObservation event) {
        Objects.requireNonNull(event, "event");
        @SuppressWarnings("unchecked")
        Class<? extends JournalEventObservation> eventType =
                (Class<? extends JournalEventObservation>) event.getClass();
        return classify(eventType);
    }

    public EventSignificance classify(
            Class<? extends JournalEventObservation> eventType
    ) {
        Objects.requireNonNull(eventType, "eventType");
        // A variant answers through its record: whether a journal event is
        // structurally significant is a property of the event, not of which
        // step of it this instance turned out to be.
        if (JournalEventLookup.covers(BOUNDARY_TYPES, eventType)) {
            return EventSignificance.BOUNDARY;
        }
        if (JournalEventLookup.covers(SIGNIFICANT_TYPES, eventType)) {
            return EventSignificance.SIGNIFICANT;
        }
        if (JournalEventLookup.covers(CONTEXT_TYPES, eventType)) {
            return EventSignificance.CONTEXT;
        }
        return EventSignificance.NOISE;
    }

    public boolean isExplicitNoise(
            Class<? extends JournalEventObservation> eventType
    ) {
        return EXPLICIT_NOISE_TYPES.contains(
                Objects.requireNonNull(eventType, "eventType")
        );
    }

    public Set<Class<? extends JournalEventObservation>> significantTypes() {
        return SIGNIFICANT_TYPES;
    }

    public Set<Class<? extends JournalEventObservation>> contextTypes() {
        return CONTEXT_TYPES;
    }

    private static void requireDisjoint(
            Set<Class<? extends JournalEventObservation>> first,
            Set<Class<? extends JournalEventObservation>> second
    ) {
        for (Class<? extends JournalEventObservation> eventType : first) {
            if (second.contains(eventType)) {
                throw new ExceptionInInitializerError(
                        "Behavior event classification overlaps for "
                                + eventType.getName()
                );
            }
        }
    }

    public enum EventSignificance {
        SIGNIFICANT,
        CONTEXT,
        NOISE,
        BOUNDARY
    }
}
