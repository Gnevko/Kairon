package kairon.observer.decision;

import kairon.observer.decision.DecisionEventProjector.ProjectedEvent;
import kairon.semantics.SemanticChangeKind;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticSourceRole;
import kairon.semantics.SemanticStateChange;
import kairon.semantics.SemanticValue;
import kairon.state.CurrentGameStateSemantics;
import kairon.state.CurrentGameStateSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which canonical changes are worth telling the model about.
 *
 * <p>The full exact delta is still computed inside the projection boundary and
 * still reaches the trace and diagnostics untouched. This decides only what a
 * decision needs, and the default is no: a change is sent when it adds
 * something the events do not already say, or when omitting it would let the
 * events be read wrongly.</p>
 *
 * <p>Eleven reasons to drop one, each of them a claim that can be checked:</p>
 * <ol>
 *   <li>the field has no model-facing name at all — an account identifier, a
 *       vessel id, a system address beside a system name, a raw taxon key;</li>
 *   <li>the causing event's mechanism already states the field, so a supercruise
 *       entry does not also report that the flight mode became supercruise;</li>
 *   <li>an event in this request already states the same canonical field at the
 *       same value — both halves, so a landing reporting
 *       {@code occurrenceOnBody: 1} no longer counts as having stated every
 *       field whose value happens to be one;</li>
 *   <li>the change is a clearing — "no longer known" is what the absence of the
 *       field from the context already says, and whatever replaced it arrives as
 *       its own change;</li>
 *   <li>the change establishes {@code activeOrganicSampling} as inactive for the
 *       first time, which is Kairon learning the flag's value rather than a
 *       sequence ending;</li>
 *   <li>the text differs only in case, which is a normalisation artefact rather
 *       than a change in the world;</li>
 *   <li>the observation was kept for diagnostics only;</li>
 *   <li>the turn is the session's identity bootstrap, where every field is being
 *       established for the first time and none of it is news;</li>
 *   <li>a hidden observation changed a subject none of this turn's mechanisms
 *       has any business hearing about — a chat message is not clarified by a
 *       ship having been loaded a minute earlier;</li>
 *   <li>a hidden observation reported a value that something after it has
 *       already replaced — see {@link #stale}.</li>
 *   <li>the change is a body's, and the events are about a different body —
 *       see {@link DecisionBodyScope}. Canonical body facts answer for where
 *       the ship is, and an arrival star's type or distance beside an event
 *       about a scanned planet is two bodies reported as one.</li>
 * </ol>
 */
public final class DecisionChangeSelector {

    private final DecisionOrganicNames organicNames;

    public DecisionChangeSelector(DecisionOrganicNames organicNames) {
        this.organicNames = Objects.requireNonNull(organicNames, "organicNames");
    }

    public List<LlmDecisionRequest.Change> select(
            DecisionTurnInputs inputs,
            List<ProjectedEvent> events,
            StatedFacts stated
    ) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(stated, "stated");
        if (isBootstrap(events)) {
            return List.of();
        }
        Map<Long, ProjectedEvent> byBusSequence = new LinkedHashMap<>();
        for (ProjectedEvent event : events) {
            byBusSequence.put(event.busSequence(), event);
        }
        Set<String> inScope = new LinkedHashSet<>();
        for (ProjectedEvent event : events) {
            inScope.addAll(event.contextProfile().subjectsInScope());
        }
        Map<GroupKey, List<LlmDecisionRequest.FieldChange>> grouped =
                new LinkedHashMap<>();
        CurrentGameStateSnapshot finalState =
                inputs.finalTrigger().currentState();
        boolean bodyInScope =
                DecisionBodyScope.canonicalBodyIsInScope(events, finalState);
        Map<Long, SemanticStateChange> organismIdentities =
                organismIdentities(inputs);
        for (SemanticStateChange change : allChanges(inputs)) {
            ProjectedEvent cause =
                    byBusSequence.get(change.provenance().busSequence());
            if (!bodyInScope
                    && DecisionBodyScope.isBodyField(change.field())) {
                continue;
            }
            if (!relevant(change, cause, stated, inScope, finalState)) {
                continue;
            }
            GroupKey key = new GroupKey(
                    change.provenance().busSequence(),
                    cause == null ? null : cause.event().id(),
                    DecisionNames.subject(change.field().subject()),
                    change.changeKind()
            );
            SemanticStateChange identities = organismIdentities.get(
                    change.provenance().busSequence()
            );
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new LlmDecisionRequest.FieldChange(
                            DecisionNames.field(change.field()),
                            named(change.field(), change.before(),
                                    identities == null ? null : identities.before()),
                            named(change.field(), change.after(),
                                    identities == null ? null : identities.after())
                    ));
        }
        List<LlmDecisionRequest.Change> result =
                new ArrayList<>(grouped.size());
        grouped.forEach((key, fields) -> result.add(
                new LlmDecisionRequest.Change(
                        key.eventId(),
                        key.subject(),
                        key.changeKind().name(),
                        fields
                )
        ));
        return List.copyOf(result);
    }

    /**
     * The organism identity change each observation carried, by bus sequence.
     *
     * <p>Canonical state keeps the organism twice — the game's
     * {@code $Codex_Ent_…_Name;} symbol and the game's rendering of it — and
     * they move together, in one observation, because they are two components
     * of one sampling process. The document sends the rendering under
     * {@code organism} and never sends the symbol, so the symbol has to be
     * looked up beside its own change to name what the rendering names.</p>
     *
     * <p>Only the identity field is collected here, and only so that
     * {@link #named} can resolve it. Nothing about which changes are selected
     * depends on this map: a change is judged, suppressed and grouped on the
     * values the journal established, and the registry is consulted once, at
     * the moment the value is written into the document. That is the same
     * separation as everywhere else — identity is compared, a name is
     * presentation.</p>
     */
    private static Map<Long, SemanticStateChange> organismIdentities(
            DecisionTurnInputs inputs
    ) {
        Map<Long, SemanticStateChange> byBusSequence = new LinkedHashMap<>();
        for (SemanticStateChange change : allChanges(inputs)) {
            if (change.field() == SemanticField.ORGANIC_SAMPLING_VARIANT) {
                byBusSequence.put(change.provenance().busSequence(), change);
            }
        }
        return byBusSequence;
    }

    /**
     * One side of a change, named through the registry where it is an organism.
     *
     * <p>Every other field is written exactly as canonical state established
     * it. An absent or unnameable organism keeps the journal's own word, which
     * is what {@link DecisionOrganicNames} falls back to.</p>
     */
    private SemanticValue named(
            SemanticField field,
            SemanticValue value,
            SemanticValue identity
    ) {
        if (field != SemanticField.ORGANIC_SAMPLING_VARIANT_LABEL
                || !value.known()) {
            return value;
        }
        SemanticValue named = organicNames.name(identity, value);
        return named.known() ? named : value;
    }

    private static List<SemanticStateChange> allChanges(
            DecisionTurnInputs inputs
    ) {
        List<SemanticStateChange> all = new ArrayList<>(
                inputs.semanticEffects().coalescedStateChanges()
        );
        inputs.semanticEffects()
                .envelopes()
                .forEach(envelope -> all.addAll(envelope.stateChanges()));
        return all;
    }

    private static boolean relevant(
            SemanticStateChange change,
            ProjectedEvent cause,
            StatedFacts stated,
            Set<String> inScope,
            CurrentGameStateSnapshot finalState
    ) {
        SemanticField field = change.field();
        if (DecisionNames.field(field) == null) {
            return false;
        }
        if (cause == null
                && !inScope.contains(DecisionNames.subject(field.subject()))) {
            return false;
        }
        if (cause == null && stale(change, finalState)) {
            return false;
        }
        if (change.changeKind() == SemanticChangeKind.CLEARED) {
            return false;
        }
        if (initialisedToInactive(change)) {
            return false;
        }
        if (change.provenance().sourceRole()
                == SemanticSourceRole.DIAGNOSTIC_ONLY) {
            return false;
        }
        if (cause != null && cause.mechanism().states(field)) {
            return false;
        }
        if (caseOnly(change.before(), change.after())) {
            return false;
        }
        return !stated.statesFact(field, change.after());
    }

    /**
     * Whether a hidden observation's value has since been replaced.
     *
     * <p>An observation the model is not being shown keeps its effect until a
     * turn closes over it, and several observations can move the same field in
     * between. The effect that survives the other rules is then whichever one
     * happened to, not whichever one is true: a restored session establishing
     * {@code flightMode = NORMAL_SPACE} outlived the supercruise jump that
     * replaced it, and arrived in a turn whose canonical state already said
     * {@code SUPERCRUISE}. Worse, its presence displaced the correct value
     * from the context, so the only thing the document said about the flight
     * mode was the wrong thing.</p>
     *
     * <p>Compared as typed semantic values against the same canonical snapshot
     * the context is selected from, through the one reader that already exists
     * for it. Never as serialized text, and never by re-deriving the field.</p>
     *
     * <p>Only for a change no event of this request caused. A trigger-owned
     * change is attributed, and an event of the batch really can report an
     * intermediate step that a later event of the same batch moved on from —
     * the {@code eventId} says whose step it was.</p>
     *
     * <p>A clearing is never stale by this rule: its {@code after} is unknown,
     * and a field that is genuinely absent from the final state reads unknown
     * too. What happens to clearings is settled elsewhere, unchanged.</p>
     */
    private static boolean stale(
            SemanticStateChange change,
            CurrentGameStateSnapshot finalState
    ) {
        return !change.after().equals(CurrentGameStateSemantics.valueOf(
                change.field(),
                finalState
        ));
    }

    /**
     * The session's opening turn, where nothing has a previous value.
     *
     * <p>Every field the identity mechanism establishes is being learned for
     * the first time. Reporting that as change would make the Commander's own
     * name, ship and location read as news.</p>
     */
    private static boolean isBootstrap(List<ProjectedEvent> events) {
        return events.stream().allMatch(
                event -> event.mechanism() == DecisionMechanism.IDENTITY
        );
    }

    /**
     * Learning that nothing is being sampled is not something that happened.
     *
     * <p>{@code activeOrganicSampling} starts unestablished and becomes
     * {@code false} the first time anything deselects a body — a jump, a
     * supercruise entry, a commander switch. That is Kairon finding out the
     * flag's value, not the game reporting that a sequence finished or was
     * interrupted, and the two are indistinguishable to a reader who is only
     * shown {@code active: false}.</p>
     *
     * <p>Deliberately narrow. It is the establishment of <em>this</em> flag to
     * <em>false</em> and nothing else: a sequence starting, and a running
     * sequence ending, are real transitions and pass through untouched.</p>
     */
    static boolean initialisedToInactive(SemanticStateChange change) {
        return change.field() == SemanticField.ACTIVE_ORGANIC_SAMPLING
                && change.changeKind() == SemanticChangeKind.ESTABLISHED
                && change.after() instanceof SemanticValue.BooleanValue active
                && !active.value();
    }

    /** {@code "explorer_nx"} to {@code "Explorer_NX"} is not a new ship. */
    private static boolean caseOnly(SemanticValue before, SemanticValue after) {
        String left = plainText(before);
        String right = plainText(after);
        return left != null
                && right != null
                && !left.equals(right)
                && left.equalsIgnoreCase(right);
    }

    private static String plainText(SemanticValue value) {
        return switch (value) {
            case SemanticValue.TextValue text -> text.value();
            case SemanticValue.SymbolicValue symbol -> symbol.symbol();
            default -> null;
        };
    }

    private record GroupKey(
            long busSequence,
            Integer eventId,
            String subject,
            SemanticChangeKind changeKind
    ) {
    }
}
