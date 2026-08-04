package kairon.semantics;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.event.combat.CockpitBreached;
import kairon.observation.journal.event.combat.HeatDamage;
import kairon.observation.journal.event.combat.SelfDestruct;
import kairon.observation.journal.event.combat.SystemsShutdown;
import kairon.observation.journal.event.ship.FighterDestroyed;
import kairon.observation.journal.event.ship.LaunchFighter;
import kairon.observation.journal.event.ship.SRVDestroyed;
import kairon.observation.journal.event.social.CrewMemberJoins;
import kairon.observation.journal.event.social.CrewMemberQuits;
import kairon.observation.journal.event.social.WingLeave;
import kairon.observation.journal.event.travel.Disembark;
import kairon.observation.journal.event.travel.DropshipDeploy;
import kairon.observation.journal.event.travel.Embark;

import java.util.Set;

/**
 * The explicitly declared exceptions to the derived disposition rule.
 *
 * <p>Most model-eligible types are {@link SemanticDisposition#STRUCTURED}
 * because an adapter covers them, and everything outside the selection profile
 * is {@link SemanticDisposition#DIAGNOSTIC_ONLY}. Only the two exceptional
 * cases below need naming, and each name is a claim that must be defensible.</p>
 *
 * <p>Deliberately holds no registry reference, so it can be consulted during
 * registry construction without a static-initialisation cycle.</p>
 */
public final class SemanticDispositions {

    /**
     * Model-eligible types whose payload carries no further critical fact.
     *
     * <p>Each of these journal events has no fields beyond the timestamp and
     * discriminator: the operation itself is the entire content, so there is
     * nothing further to structure and nothing is being hidden.</p>
     */
    private static final Set<Class<? extends JournalEventObservation>>
            NO_CRITICAL_STRUCTURED_FACTS = Set.of(
                    CockpitBreached.class,
                    HeatDamage.class,
                    SelfDestruct.class,
                    SystemsShutdown.class,
                    WingLeave.class
            );

    /**
     * Model-eligible types with a genuinely unprovable critical aspect.
     *
     * <p>All of them concern who or what is physically aboard which vessel.
     * The canonical projection keeps one vehicle slot, models neither taxi nor
     * multicrew, and treats fighter control as unrelated to physical presence,
     * so occupancy cannot be established. The adapters still produce every
     * fact that <em>is</em> provable and record the gap explicitly.</p>
     */
    private static final Set<Class<? extends JournalEventObservation>>
            UNRESOLVED_AUTHORITATIVE_SEMANTICS = Set.of(
                    LaunchFighter.class,
                    FighterDestroyed.class,
                    SRVDestroyed.class,
                    Disembark.class,
                    Embark.class,
                    DropshipDeploy.class,
                    CrewMemberJoins.class,
                    CrewMemberQuits.class
            );

    private SemanticDispositions() {
    }

    public static boolean noCriticalStructuredFacts(
            Class<? extends JournalEventObservation> eventType
    ) {
        return NO_CRITICAL_STRUCTURED_FACTS.contains(eventType);
    }

    public static boolean unresolvedAuthoritativeSemantics(
            Class<? extends JournalEventObservation> eventType
    ) {
        return UNRESOLVED_AUTHORITATIVE_SEMANTICS.contains(eventType);
    }

    public static int declaredExceptionCount() {
        return NO_CRITICAL_STRUCTURED_FACTS.size()
                + UNRESOLVED_AUTHORITATIVE_SEMANTICS.size();
    }
}
