package kairon.observer.decision;

import kairon.observer.decision.DecisionEventProjector.ProjectedEvent;
import kairon.observer.decision.DecisionContextProfile.ContextNeed;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticValue;
import kairon.state.CurrentGameStateSemantics;
import kairon.state.CurrentGameStateSnapshot;
import kairon.semantics.BodySurveyFacts;
import kairon.system.BiologicalSurvey;
import kairon.system.PlanetBody;
import kairon.system.StarBody;
import kairon.system.SystemObject;
import kairon.system.SystemObjectKind;
import kairon.system.SystemRegistrySnapshot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

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
            SystemRegistrySnapshot registry,
            List<ProjectedEvent> events,
            List<LlmDecisionRequest.Change> changes,
            StatedFacts eventFacts
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(registry, "registry");
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
        addSystem(groups, state, registry, needs, stated);
        if (DecisionBodyScope.canonicalBodyIsInScope(events, state)) {
            addBody(groups, state, registry, needs, stated);
            addBiology(groups, state, registry, needs);
        }
        addNavigation(groups, state, needs, stated);
        addCommander(groups, state, needs, stated);
        addShip(groups, state, needs, stated);
        addVehicle(groups, state, needs, stated);
        addSampling(groups, state, needs, stated, events);
        return List.copyOf(groups);
    }

    /**
     * The system, and how much of it has been read.
     *
     * <p>The two counts come from the current-system registry, because that is
     * what holds them. Without them a survey turn says only what this one
     * reading found, and nothing in the request can contradict a comment that
     * calls the eleventh body of a system the first — which is what a measured
     * run did say.</p>
     *
     * <p>Both or neither. How much has been read is progress only against how
     * much there is, and a bare numerator is worse than silence: before the
     * discovery scan states a total, the arrival star's own milestone turn
     * carried {@code scannedCount: 1} — the reading that turn is about, counted
     * back to it as though it were background.</p>
     *
     * <p>A count of zero is absent too. Right after the honk "nothing read yet"
     * is what the field's absence already says, and a zero repeated through
     * every turn is the shape this contract removed from the sampling
     * group.</p>
     */
    private static void addSystem(
            List<LlmDecisionRequest.ContextGroup> groups,
            CurrentGameStateSnapshot state,
            SystemRegistrySnapshot registry,
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
        Integer total = describesCurrentSystem(registry, state)
                ? registry.bodyCount()
                : null;
        if (total != null) {
            add(
                    facts,
                    stated,
                    SemanticField.SYSTEM_BODY_COUNT,
                    SemanticValue.ofIntegral(total)
            );
            long scanned = registry.scannedBodyCount();
            add(
                    facts,
                    stated,
                    SemanticField.SYSTEM_SCANNED_COUNT,
                    scanned > 0
                            ? SemanticValue.ofIntegral(scanned)
                            : SemanticValue.unknown()
            );
        }
        group(groups, "system", facts);
    }

    /** Whether the registry is describing the system the ship is in. */
    private static boolean describesCurrentSystem(
            SystemRegistrySnapshot registry,
            CurrentGameStateSnapshot state
    ) {
        return registry.available()
                && state.systemAddress() != null
                && Objects.equals(
                        registry.systemAddress(),
                        state.systemAddress()
                );
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
            SystemRegistrySnapshot registry,
            Set<ContextNeed> needs,
            StatedFacts stated
    ) {
        boolean detail = needs.contains(ContextNeed.BODY_DETAIL);
        if (!detail && !needs.contains(ContextNeed.BODY_IDENTITY)) {
            return;
        }
        SystemObject body = bodyInRegistry(registry, state);
        List<LlmDecisionRequest.Field> facts = new ArrayList<>();
        add(
                facts,
                stated,
                SemanticField.BODY_NAME,
                SemanticValue.ofText(state.bodyName())
        );
        if (detail && body != null) {
            add(
                    facts,
                    stated,
                    SemanticField.BROAD_BODY_TYPE,
                    body.kind() == SystemObjectKind.UNCLASSIFIED
                            ? SemanticValue.unknown()
                            : SemanticValue.ofSymbol(body.kind().name())
            );
            add(
                    facts,
                    stated,
                    SemanticField.PLANET_CLASS,
                    body instanceof PlanetBody planet
                            ? SemanticValue.ofText(planet.planetClass())
                            : SemanticValue.unknown()
            );
            add(
                    facts,
                    stated,
                    SemanticField.STAR_TYPE,
                    body instanceof StarBody star
                            ? SemanticValue.ofText(star.starType())
                            : SemanticValue.unknown()
            );
            add(
                    facts,
                    stated,
                    SemanticField.LANDABLE,
                    body instanceof PlanetBody planet
                            ? SemanticValue.ofBoolean(planet.landable())
                            : SemanticValue.unknown()
            );
            // Only where the ship can put down. On a gas giant the pull is a
            // number about a place nothing stands on, and "high gravity" beside
            // a body no one can land on reads as a warning about a landing that
            // was never possible.
            add(
                    facts,
                    stated,
                    SemanticField.SURFACE_GRAVITY,
                    body instanceof PlanetBody planet
                            && Boolean.TRUE.equals(planet.landable())
                            ? DecisionNames.gravityBand(planet.surfaceGravity())
                            : SemanticValue.unknown()
            );
            add(
                    facts,
                    stated,
                    SemanticField.WAS_DISCOVERED,
                    SemanticValue.ofBoolean(body.profile().wasDiscovered())
            );
            add(
                    facts,
                    stated,
                    SemanticField.WAS_MAPPED,
                    SemanticValue.ofBoolean(body.profile().wasMapped())
            );
            add(
                    facts,
                    stated,
                    SemanticField.WAS_FOOTFALLED,
                    SemanticValue.ofBoolean(body.profile().wasFootfalled())
            );
            add(
                    facts,
                    stated,
                    SemanticField.BIOLOGICAL_SIGNAL_COUNT,
                    signalCount(body, BodySurveyFacts.BIOLOGICAL)
            );
            add(
                    facts,
                    stated,
                    SemanticField.GEOLOGICAL_SIGNAL_COUNT,
                    signalCount(body, BodySurveyFacts.GEOLOGICAL)
            );
        }
        group(groups, "body", facts);
    }

    /**
     * The registry's entry for the body canonical state has selected.
     *
     * <p>Null when the registry is describing another system or holds nothing
     * for this body. Both are the fail-closed answer: the group then carries
     * the body's name and nothing about what it is, which is exactly what is
     * established.</p>
     */
    private static SystemObject bodyInRegistry(
            SystemRegistrySnapshot registry,
            CurrentGameStateSnapshot state
    ) {
        if (state.bodyId() == null
                || !describesCurrentSystem(registry, state)) {
            return null;
        }
        return registry.object(state.bodyId());
    }

    private static SemanticValue signalCount(
            SystemObject body,
            String category
    ) {
        return SemanticValue.ofIntegral(
                body.profile().signalCounts().get(category)
        );
    }

    /**
     * What grows on this body and what has been collected of it.
     *
     * <p>The one thing in the request that comes from the current-system
     * registry rather than from canonical state, and it has to: only
     * {@code SAASignalsFound} names the genera, and when it restates counts the
     * system scanner already reported it opens no turn at all. The names would
     * otherwise live for exactly one observation that the model never sees.</p>
     *
     * <p>One field per genus, named by the organism and valued
     * {@code COLLECTED} or {@code NOT_COLLECTED}. Not one field carrying a list:
     * {@link SemanticValue} is a closed set with no list in it, and adding one
     * would put back the compound value ADR-0024 removed. A genus the game has
     * no word for at all is still left out — that rule is unchanged — but the
     * name of the ones that stay is read off the identity by
     * {@link DecisionNames#genusField} rather than off the localised label. The
     * label is in whatever language the game runs in, and with a Russian client
     * this group was the one Cyrillic key in an English document: the document's
     * own names moved with the output language while nothing else did.</p>
     *
     * <p>The whole inventory is sent, including a genus the turn's own event has
     * just finished. That is not the event said twice: the event reports an
     * action, this reports what is standing on the body, and its worth is in
     * being complete — a list with the just-collected organism missing would
     * read as a list of what is left, one item short.</p>
     *
     * <p>Absent when no survey has named anything. A biological signal count
     * without names is how many there are and not which, and a body with three
     * signals and no survey would otherwise read as three organisms nobody has
     * collected.</p>
     */
    private static void addBiology(
            List<LlmDecisionRequest.ContextGroup> groups,
            CurrentGameStateSnapshot state,
            SystemRegistrySnapshot registry,
            Set<ContextNeed> needs
    ) {
        if (!needs.contains(ContextNeed.BIOLOGY)) {
            return;
        }
        SystemObject body = bodyInRegistry(registry, state);
        if (body == null) {
            return;
        }
        BiologicalSurvey survey = body.biology();
        if (survey.genera().isEmpty()) {
            return;
        }
        Map<String, String> named = new TreeMap<>();
        for (Map.Entry<String, String> genus : survey.genera().entrySet()) {
            if (genus.getValue() == null) {
                // The game has no word for this organism at all; see below.
                continue;
            }
            String name = DecisionNames.genusField(genus.getKey());
            if (name != null) {
                named.put(name, genus.getKey());
            }
        }
        List<LlmDecisionRequest.Field> facts = named.entrySet().stream()
                .map(genus -> new LlmDecisionRequest.Field(
                        genus.getKey(),
                        SemanticValue.ofSymbol(
                                survey.completed().contains(genus.getValue())
                                        ? "COLLECTED"
                                        : "NOT_COLLECTED"
                        )
                ))
                .toList();
        group(groups, "biology", facts);
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
