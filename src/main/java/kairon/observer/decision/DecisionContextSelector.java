package kairon.observer.decision;

import kairon.observer.decision.DecisionEventProjector.ProjectedEvent;
import kairon.observer.decision.DecisionContextProfile.ContextNeed;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticValue;
import kairon.state.CurrentGameStateSemantics;
import kairon.state.CurrentGameStateSnapshot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The slice of the current situation these events need, and no more.
 *
 * <p>The full canonical state still exists and is still complete; what changes
 * is that only the mechanisms present in this turn get to ask it anything. A
 * pair of friend notifications asks nothing, so a turn about friends carries no
 * state at all. A landing asks for the body and how the ship is travelling. A
 * sample asks for the body, where the Commander is standing, and the sequence
 * already in progress.</p>
 *
 * <p>Two further rules keep it from restating the turn. A fact whose value an
 * event or a change already carries is dropped, because the model would read
 * the same value twice and might count it twice. And a group with nothing left
 * in it is not sent as an empty object: absence is how this contract says
 * "unknown or not relevant".</p>
 *
 * <p>The body group has a third: it is only sent when canonical state answers
 * for the body the events are about. Scanning a planet from across the system
 * does not move the ship, so the body Kairon knows about is still the arrival
 * star — and sending the star's class and distance under {@code body} beside an
 * event about a planet describes two bodies as one. See
 * {@link DecisionBodyScope}.</p>
 */
public final class DecisionContextSelector {

    public List<LlmDecisionRequest.ContextGroup> select(
            CurrentGameStateSnapshot state,
            List<ProjectedEvent> events,
            List<LlmDecisionRequest.Change> changes,
            StatedFacts eventFacts
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(changes, "changes");
        Objects.requireNonNull(eventFacts, "eventFacts");
        Set<ContextNeed> needs = EnumSet.noneOf(ContextNeed.class);
        for (ProjectedEvent event : events) {
            needs.addAll(event.contextProfile().contextNeeds());
        }
        if (needs.isEmpty()) {
            return List.of();
        }
        StatedFacts stated = eventFacts.withChanges(changes);
        List<LlmDecisionRequest.ContextGroup> groups = new ArrayList<>();
        addSystem(groups, state, needs, stated);
        if (DecisionBodyScope.canonicalBodyIsInScope(events, state)) {
            addBody(groups, state, needs, stated);
        }
        addNavigation(groups, state, needs, stated);
        addCommander(groups, state, needs, stated);
        addShip(groups, state, needs, stated);
        addVehicle(groups, state, needs, stated);
        addSampling(groups, state, needs, stated, events);
        return List.copyOf(groups);
    }

    private static void addSystem(
            List<LlmDecisionRequest.ContextGroup> groups,
            CurrentGameStateSnapshot state,
            Set<ContextNeed> needs,
            StatedFacts stated
    ) {
        if (!needs.contains(ContextNeed.SYSTEM)) {
            return;
        }
        List<LlmDecisionRequest.Field> facts = new ArrayList<>();
        add(
                facts,
                stated,
                SemanticField.SYSTEM_NAME,
                SemanticValue.ofText(state.systemName())
        );
        group(groups, "system", facts);
    }

    /**
     * The body, at the depth the turn justifies.
     *
     * <p>Approaching or leaving one needs its name; landing on it, surveying it
     * or sampling on it needs what is known about it. The coarse type and the
     * specific class answer different questions — planet or star, and which
     * kind of planet — so both are sent when both are established.</p>
     *
     * <p>The survey flags are named {@code previouslyDiscovered},
     * {@code previouslyMapped} and {@code previouslyFootfalled}. They record
     * what was true before this arrival, and a bare {@code discovered} beside an
     * approach reads as something that just happened.</p>
     */
    private static void addBody(
            List<LlmDecisionRequest.ContextGroup> groups,
            CurrentGameStateSnapshot state,
            Set<ContextNeed> needs,
            StatedFacts stated
    ) {
        boolean detail = needs.contains(ContextNeed.BODY_DETAIL);
        if (!detail && !needs.contains(ContextNeed.BODY_IDENTITY)) {
            return;
        }
        List<LlmDecisionRequest.Field> facts = new ArrayList<>();
        add(
                facts,
                stated,
                SemanticField.BODY_NAME,
                SemanticValue.ofText(state.bodyName())
        );
        if (detail) {
            SemanticValue planetClass =
                    SemanticValue.ofText(state.planetClass());
            SemanticValue starType = SemanticValue.ofText(state.starType());
            add(
                    facts,
                    stated,
                    SemanticField.BROAD_BODY_TYPE,
                    DecisionNames.closedToken(
                            SemanticValue.ofSymbol(state.broadBodyType())
                    )
            );
            add(
                    facts,
                    stated,
                    SemanticField.PLANET_CLASS,
                    planetClass
            );
            add(facts, stated, SemanticField.STAR_TYPE, starType);
            add(
                    facts,
                    stated,
                    SemanticField.LANDABLE,
                    SemanticValue.ofBoolean(state.landable())
            );
            add(
                    facts,
                    stated,
                    SemanticField.WAS_DISCOVERED,
                    SemanticValue.ofBoolean(state.wasDiscovered())
            );
            add(
                    facts,
                    stated,
                    SemanticField.WAS_MAPPED,
                    SemanticValue.ofBoolean(state.wasMapped())
            );
            add(
                    facts,
                    stated,
                    SemanticField.WAS_FOOTFALLED,
                    SemanticValue.ofBoolean(state.wasFootfalled())
            );
            add(
                    facts,
                    stated,
                    SemanticField.DISTANCE_FROM_ARRIVAL_LS,
                    SemanticValue.ofDecimal(state.distanceFromArrivalLs())
            );
            add(
                    facts,
                    stated,
                    SemanticField.BIOLOGICAL_SIGNAL_COUNT,
                    SemanticValue.ofIntegral(state.biologicalSignalCount())
            );
            add(
                    facts,
                    stated,
                    SemanticField.GEOLOGICAL_SIGNAL_COUNT,
                    SemanticValue.ofIntegral(state.geologicalSignalCount())
            );
        }
        group(groups, "body", facts);
    }

    private static void addNavigation(
            List<LlmDecisionRequest.ContextGroup> groups,
            CurrentGameStateSnapshot state,
            Set<ContextNeed> needs,
            StatedFacts stated
    ) {
        if (!needs.contains(ContextNeed.NAVIGATION)) {
            return;
        }
        List<LlmDecisionRequest.Field> facts = new ArrayList<>();
        add(
                facts,
                stated,
                SemanticField.FLIGHT_MODE,
                symbol(state.flightMode())
        );
        group(groups, "navigation", facts);
    }

    /**
     * Where the Commander physically is.
     *
     * <p>The only Commander fact that reaches the model. The account identifier
     * has no representation here at all, so there is nothing for a request to
     * accidentally include.</p>
     */
    private static void addCommander(
            List<LlmDecisionRequest.ContextGroup> groups,
            CurrentGameStateSnapshot state,
            Set<ContextNeed> needs,
            StatedFacts stated
    ) {
        if (!needs.contains(ContextNeed.PRESENCE)) {
            return;
        }
        List<LlmDecisionRequest.Field> facts = new ArrayList<>();
        add(
                facts,
                stated,
                SemanticField.COMMANDER_MODE,
                symbol(state.commanderMode())
        );
        group(groups, "commander", facts);
    }

    private static void addShip(
            List<LlmDecisionRequest.ContextGroup> groups,
            CurrentGameStateSnapshot state,
            Set<ContextNeed> needs,
            StatedFacts stated
    ) {
        if (!needs.contains(ContextNeed.SHIP)) {
            return;
        }
        List<LlmDecisionRequest.Field> facts = new ArrayList<>();
        // Ship type case is left exactly as the journal wrote it: the case
        // semantics are unresolved, so normalising here would assert one.
        add(
                facts,
                stated,
                SemanticField.SHIP_TYPE,
                SemanticValue.ofText(state.shipType())
        );
        add(
                facts,
                stated,
                SemanticField.SHIP_NAME,
                SemanticValue.ofText(state.shipName())
        );
        group(groups, "ship", facts);
    }

    /**
     * The auxiliary vehicle associated with the Commander.
     *
     * <p>Association is not occupancy, and this group never claims otherwise:
     * it carries the kind of vehicle and nothing about who is inside it.</p>
     */
    private static void addVehicle(
            List<LlmDecisionRequest.ContextGroup> groups,
            CurrentGameStateSnapshot state,
            Set<ContextNeed> needs,
            StatedFacts stated
    ) {
        if (!needs.contains(ContextNeed.VEHICLE)) {
            return;
        }
        List<LlmDecisionRequest.Field> facts = new ArrayList<>();
        if (!CurrentGameStateSnapshot.VEHICLE_UNKNOWN
                .equals(state.vehicleKind())) {
            add(
                    facts,
                    stated,
                    SemanticField.VEHICLE_KIND,
                    SemanticValue.ofSymbol(state.vehicleKind())
            );
        }
        group(groups, "vehicle", facts);
    }

    /**
     * A running organic sampling sequence, when one is running.
     *
     * <p>An inactive process is absent rather than reported as inactive. The
     * previous contract sent {@code active: false} in thirteen turns that had
     * nothing to do with sampling, which is a declaration of absence in a
     * contract whose whole rule is that absence needs no declaration. A
     * finished sequence is absent for the same reason and by the same route:
     * completing one clears it, so there is nothing here to describe.</p>
     *
     * <p>The stage is said in the tense a standing fact is in — {@code STARTED},
     * {@code IN_PROGRESS} — while the event beside it keeps the tense an event
     * is in. {@link DecisionNames#samplingContextStage} is where the two
     * vocabularies are held apart.</p>
     *
     * <p>Absent from the sampling event's own turn. The group exists because a
     * sequence outlives the events that are not about it — a Commander logs a
     * plant, drives back, lands again, and by then nothing in the request says a
     * sequence is running. A scan is not one of those events: it reports the
     * organism, the position it just reached and whether that finished it, so
     * the standing description beside it is the same sequence said twice, once
     * in each vocabulary. Two spellings of one position — {@code PROGRESS} and
     * {@code IN_PROGRESS} — is exactly the shape a reader has to work out is not
     * two things.</p>
     *
     * <p>Read off the mechanism rather than the kind: the sampling mechanism is
     * what makes an event a statement about the sequence, and it is the same
     * mechanism that asks for this group in the first place. Nothing else
     * changes — a presence event during a running sequence still carries it, and
     * so does every other mechanism that asks.</p>
     */
    private static void addSampling(
            List<LlmDecisionRequest.ContextGroup> groups,
            CurrentGameStateSnapshot state,
            Set<ContextNeed> needs,
            StatedFacts stated,
            List<ProjectedEvent> events
    ) {
        if (!needs.contains(ContextNeed.SAMPLING)
                || !Boolean.TRUE.equals(state.activeOrganicSampling())
                || statesTheSequenceItself(events)) {
            return;
        }
        List<LlmDecisionRequest.Field> facts = new ArrayList<>();
        add(
                facts,
                stated,
                SemanticField.ORGANIC_SAMPLING_VARIANT_LABEL,
                CurrentGameStateSemantics.valueOf(
                        SemanticField.ORGANIC_SAMPLING_VARIANT_LABEL,
                        state
                )
        );
        add(
                facts,
                stated,
                SemanticField.ORGANIC_SAMPLING_STAGE,
                DecisionNames.samplingContextStage(
                        CurrentGameStateSemantics.valueOf(
                                SemanticField.ORGANIC_SAMPLING_STAGE,
                                state
                        )
                )
        );
        group(groups, "sampling", facts);
    }

    /** Whether an event of this turn is itself a step of the sequence. */
    private static boolean statesTheSequenceItself(
            List<ProjectedEvent> events
    ) {
        return events.stream().anyMatch(
                event -> event.mechanism() == DecisionMechanism.SAMPLING
        );
    }

    private static SemanticValue symbol(Enum<?> value) {
        return value == null || "UNKNOWN".equals(value.name())
                ? SemanticValue.unknown()
                : SemanticValue.ofSymbol(value.name());
    }

    /**
     * One canonical fact, if the turn has not already said it.
     *
     * <p>One test for every group, against the one {@link StatedFacts} the
     * change selector also read. Two ways it can already have been said, and
     * they are different questions: the slot has been answered — by a change
     * naming the same canonical field, or by an event that answers the slot
     * outright under its own word — or an event stated this exact field at this
     * exact value.</p>
     *
     * <p>The name comes from {@link DecisionNames} rather than from a literal,
     * so the two ways the model can hear about one canonical field cannot drift
     * into two spellings.</p>
     */
    private static void add(
            List<LlmDecisionRequest.Field> facts,
            StatedFacts stated,
            SemanticField field,
            SemanticValue value
    ) {
        String name = DecisionNames.field(field);
        if (name == null
                || value == null
                || !value.known()
                || stated.statesSlot(field)
                || stated.statesFact(field, value)) {
            return;
        }
        facts.add(new LlmDecisionRequest.Field(name, value));
    }

    private static void group(
            List<LlmDecisionRequest.ContextGroup> groups,
            String name,
            List<LlmDecisionRequest.Field> facts
    ) {
        if (!facts.isEmpty()) {
            groups.add(new LlmDecisionRequest.ContextGroup(
                    name,
                    List.copyOf(facts)
            ));
        }
    }

}
