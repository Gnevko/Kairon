package kairon.observer.decision;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.projection.ProjectedObservation;
import kairon.semantics.SemanticFact;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticObservationEnvelope;
import kairon.semantics.SemanticValue;
import kairon.semantics.UnresolvedFact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns one current trigger into one domain-facing event.
 *
 * <p>Shared by mechanism: the catalogue supplies four per-type decisions and
 * everything else here is the same code for every event. What that code refuses
 * to emit is the point — a subject the kind already implies, a Commander actor
 * that is always the Commander, a completion flag on an action that cannot be
 * incomplete, an identifier nobody can say aloud, and above all a bare number
 * with no name.</p>
 *
 * <p>An event carries at most one value per name. Where an adapter produced
 * several facts for one observation, the first fact to claim a name keeps it;
 * the rest contribute only what is still unnamed.</p>
 */
public final class DecisionEventProjector {

    /**
     * Attributes that no decision has been shown to need.
     *
     * <p>{@code position} is six decimal places of latitude, and no comment in
     * either measured run referenced a coordinate. {@code marketId} is an
     * internal market key. {@code subCategory} is a narrower restatement of the
     * codex category beside it. {@code bodyType} is the coarse classification
     * that every mechanism carrying it also sends a planet class or star type
     * for, in the context.</p>
     */
    private static final Set<String> DROPPED_QUALIFIERS = Set.of(
            "position",
            "marketId",
            "subCategory",
            "bodyType"
    );

    /** Attributes whose adapter name reads as a predicate rather than a fact. */
    private static final Map<String, String> RENAMED_QUALIFIERS = Map.of(
            "isNewEntry", "newEntry",
            "isPlayer", "player"
    );

    /**
     * Taxon levels the sampling mechanism folds into one speakable name.
     *
     * <p>Genus, species and variant are three nested names of the same
     * organism. Only the variant is what a comment would say.</p>
     */
    private static final Set<String> SAMPLING_TAXON_LEVELS = Set.of(
            "genus",
            "species"
    );

    public ProjectedEvent project(int localId, ProjectedObservation projected) {
        Objects.requireNonNull(projected, "projected");
        JournalEventObservation payload = payloadOf(projected);
        DecisionEventRule rule = payload == null
                ? null
                : DecisionEventCatalog.ruleFor(payload);
        if (rule == null) {
            // Unreachable for a catalogued type: every model-eligible event has
            // a rule, and a test asserts that in both directions. Reaching here
            // means an uncatalogued payload became a trigger, which fails the
            // turn rather than being guessed into a domain kind — and there is
            // no description to guess either.
            throw new IllegalStateException(
                    "an uncatalogued observation became a model trigger: "
                            + (payload == null
                            ? "no journal payload"
                            : payload.getClass().getName())
            );
        }
        String description = describe(payload);
        SemanticObservationEnvelope envelope = projected.semanticEnvelope();
        List<LlmDecisionRequest.Field> fields = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();
        for (SemanticFact fact : envelope.structuredFacts()) {
            appendFact(fields, claimed, fact, rule);
        }
        for (UnresolvedFact gap : envelope.unresolvedFacts()) {
            if (rule.wholeAction()) {
                // The kind settles the action, so there is no adjacent claim
                // for a gap to qualify; see DecisionEventRule.wholeAction.
                continue;
            }
            DecisionNames.Uncertainty uncertainty =
                    DecisionNames.uncertainty(gap.reason());
            if (uncertainty == null) {
                // A gap about a claim the event never made has nothing to
                // qualify; see DecisionNames.uncertainty.
                continue;
            }
            if (uncertainty.name().equals(rule.settledGap())) {
                // This one gap is answered outright by the same request; see
                // DecisionEventRule.settledGap.
                continue;
            }
            add(
                    fields,
                    claimed,
                    uncertainty.name(),
                    SemanticValue.ofSymbol(uncertainty.marker())
            );
        }
        appendOccurrenceCount(fields, claimed, projected, rule);
        return new ProjectedEvent(
                new LlmDecisionRequest.Event(
                        localId,
                        rule.kind(),
                        description,
                        List.copyOf(fields)
                ),
                rule.mechanism(),
                rule.contextProfile(),
                projected.busSequence()
        );
    }

    /**
     * What this event says it reports, asked of the event itself.
     *
     * <p>No lookup by class, no lookup by kind, no table: the observation in
     * hand is the one thing that knows what it means, and this narrows it and
     * asks. Every model-eligible type implements the contract — the selection
     * profile refuses to initialise otherwise — so a payload that cannot answer
     * is a broken contract rather than a case to handle.</p>
     *
     * <p>It fails the turn rather than falling back. The internal kind is not a
     * substitute: sending it would put a name only Kairon understands in the
     * one slot that tells the model what happened, which is what this contract
     * exists to stop.</p>
     */
    private static String describe(JournalEventObservation payload) {
        if (!(payload instanceof LlmPresentableJournalEvent presentable)) {
            throw new IllegalStateException(
                    "a model trigger must describe itself: "
                            + payload.getClass().getName()
                            + " does not implement "
                            + LlmPresentableJournalEvent.class.getSimpleName()
            );
        }
        String description = presentable.modelFacingDescription();
        if (description == null || description.isBlank()) {
            throw new IllegalStateException(
                    "a model trigger must describe itself: "
                            + payload.getClass().getName()
                            + " supplied no description"
            );
        }
        return description;
    }

    /**
     * How many times this has happened at this body during this system visit.
     *
     * <p>The one thing an event cannot say about itself. "Landed" and "landed
     * here for the second time" are different situations, and only the episode
     * knows which one this is. Scoped to the body rather than to the visit,
     * because a second landing on a different moon is not a repeat of
     * anything.</p>
     *
     * <p>Absent whenever the scope is not established — see
     * {@link DecisionOccurrenceScope}. A count nobody can attach to a place is
     * worse than no count.</p>
     *
     * <p>Absent too where the graph counts each stage of one kind separately —
     * see {@link DecisionEventRule#stageSpecificOccurrences()}. The count is
     * then true of a stage, the name would be read as true of the kind, and a
     * count read as the wrong thing is the same failure as a count with no
     * scope. The graph keeps counting; only this field goes.</p>
     *
     * <p>And absent where a repeat is never recorded at all — see
     * {@link DecisionEventRule#uncountedOnBody()}. A field whose only possible
     * value is one says nothing.</p>
     */
    private static void appendOccurrenceCount(
            List<LlmDecisionRequest.Field> fields,
            Set<String> claimed,
            ProjectedObservation projected,
            DecisionEventRule rule
    ) {
        if (rule.stageSpecificOccurrences() || rule.uncountedOnBody()) {
            return;
        }
        Integer count = DecisionOccurrenceScope.occurrenceOnBody(projected);
        if (count != null) {
            add(
                    fields,
                    claimed,
                    "occurrenceOnBody",
                    SemanticValue.ofIntegral(count)
            );
        }
    }

    private static void appendFact(
            List<LlmDecisionRequest.Field> fields,
            Set<String> claimed,
            SemanticFact fact,
            DecisionEventRule rule
    ) {
        appendObject(fields, claimed, fact, rule);
        appendQualifiers(fields, claimed, fact, rule);
        appendQuantity(fields, claimed, fact, rule);
        // A semantic relationship is not projected. It named its counterpart
        // with Kairon's own kind — the one vocabulary an event no longer sends
        // — so after the kind stopped being serialized the value pointed at a
        // word the model never sees. It also flattened five different
        // relations (cancels, negates, releases, inverse of, negative outcome
        // of) into one field called "reverses". The relationship is unchanged
        // on the semantic fact and still reaches diagnostics; saying it to the
        // model needs a contract of its own.
        appendProcess(fields, claimed, fact, rule);
        if (Boolean.TRUE.equals(fact.negation())) {
            add(fields, claimed, "negated", SemanticValue.ofBoolean(true));
        }
        if (fact.assertionSource() == SemanticFact.AssertionSource.DERIVED) {
            add(fields, claimed, "derived", SemanticValue.ofBoolean(true));
        }
    }

    /**
     * The thing acted on, by name.
     *
     * <p>An internal identifier is never emitted, even when the event carries
     * no name: no catalogued mechanism was found where the id is the only way
     * to tell two objects apart within a single request, and correlation across
     * a request is what the local event ids are for.</p>
     */
    private static void appendObject(
            List<LlmDecisionRequest.Field> fields,
            Set<String> claimed,
            SemanticFact fact,
            DecisionEventRule rule
    ) {
        SemanticFact.EntityRef object = fact.object();
        if (object == null || object.name() == null) {
            return;
        }
        String name = rule.objectName() != null
                ? rule.objectName()
                : DecisionNames.entity(object.kind());
        if (name == null) {
            return;
        }
        if (rule.mechanism() == DecisionMechanism.SAMPLING) {
            // The variant qualifier is the full organism name; the object
            // carries only the species, so it must not claim "organism" first.
            SemanticValue variant = fact.qualifiers().get("variant");
            if (variant != null && variant.known()) {
                return;
            }
        }
        add(fields, claimed, name, SemanticValue.ofText(object.name()));
    }

    /**
     * Whether this attribute is dropped for this event.
     *
     * <p>{@code bodyType} has one exception, and it is the event that earns it.
     * Elsewhere the coarse classification restates what the planet class or
     * star type beside it already says. A body scan is where that pair comes
     * from: {@code STAR} against {@code PLANET} is what decides which of the
     * two the reading even carries, and a scan that says neither is not a
     * scan the model sees.</p>
     */
    private static boolean dropped(String key, DecisionEventRule rule) {
        if (!rule.retainedQualifiers().isEmpty()
                && !rule.retainedQualifiers().contains(key)) {
            // The kind was derived from a record that says more than the kind
            // asserts; see DecisionEventRule.retainedQualifiers.
            return true;
        }
        if (!DROPPED_QUALIFIERS.contains(key)) {
            return false;
        }
        return !("bodyType".equals(key)
                && rule.mechanism() == DecisionMechanism.EXPLORATION);
    }

    private static void appendQualifiers(
            List<LlmDecisionRequest.Field> fields,
            Set<String> claimed,
            SemanticFact fact,
            DecisionEventRule rule
    ) {
        for (Map.Entry<String, SemanticValue> entry
                : fact.qualifiers().entrySet()) {
            String key = entry.getKey();
            if (dropped(key, rule)) {
                continue;
            }
            if (rule.mechanism() == DecisionMechanism.IDENTITY
                    && "name".equals(key)) {
                // The only name a Commander event carries is the Commander's,
                // and "name" alone leaves the model to work that out.
                add(fields, claimed, "commander", entry.getValue());
                continue;
            }
            if (rule.mechanism() == DecisionMechanism.SOCIAL
                    && "status".equals(key)) {
                add(
                        fields,
                        claimed,
                        key,
                        DecisionNames.closedToken(entry.getValue())
                );
                continue;
            }
            if (rule.mechanism() == DecisionMechanism.SOCIAL
                    && "channel".equals(key)) {
                // A received message is the only social event that names a
                // channel, and the journal names it in its own lower case.
                add(
                        fields,
                        claimed,
                        key,
                        DecisionNames.messageChannel(entry.getValue())
                );
                continue;
            }
            if (rule.mechanism() == DecisionMechanism.SAMPLING) {
                if (SAMPLING_TAXON_LEVELS.contains(key)) {
                    continue;
                }
                if ("variant".equals(key)) {
                    add(fields, claimed, "organism", entry.getValue());
                    continue;
                }
                if ("scanType".equals(key)) {
                    // Log, Sample and Analyse are the three positions in the
                    // sequence, and the adapter already turns each of them into
                    // exactly one stage and one completion — START/false,
                    // PROGRESS/false, FINAL/true. Sampling is multi-stage, so
                    // all three of those are sent. A second name for the same
                    // distinction is the same fact twice, and this was the
                    // spelling of it the model twice misread as a discovery.
                    continue;
                }
            }
            add(
                    fields,
                    claimed,
                    RENAMED_QUALIFIERS.getOrDefault(key, key),
                    entry.getValue()
            );
        }
    }

    /**
     * How much, under a name that says what it measures.
     *
     * <p>A quantity with no name is dropped rather than sent. That is the whole
     * correction: an unnamed {@code 2} beside an {@code efficiencyTarget} of 2
     * is what produced the run's one factual error.</p>
     */
    private static void appendQuantity(
            List<LlmDecisionRequest.Field> fields,
            Set<String> claimed,
            SemanticFact fact,
            DecisionEventRule rule
    ) {
        SemanticValue quantity = fact.quantity();
        if (!quantity.known()) {
            return;
        }
        String name = rule.quantityName();
        if (name == null
                && quantity instanceof SemanticValue.QuantityValue measured) {
            name = unitName(measured.unit());
        }
        if (name == null) {
            return;
        }
        add(fields, claimed, name, quantity);
    }

    /**
     * Where the action sits in a process, when that is not a constant.
     *
     * <p>An unfinished stage and an explicit false completion are always sent:
     * they are the difference between a sample taken and a sample analysed. A
     * FINAL stage and a true completion are sent only for a mechanism that has
     * more than one stage, because on an atomic action they are the only value
     * the field can have. An event whose kind states the whole action carries
     * no stage at all — its position is settled by the name.</p>
     */
    private static void appendProcess(
            List<LlmDecisionRequest.Field> fields,
            Set<String> claimed,
            SemanticFact fact,
            DecisionEventRule rule
    ) {
        SemanticFact.ProcessStage stage = fact.processStage();
        boolean unfinished = stage == SemanticFact.ProcessStage.START
                || stage == SemanticFact.ProcessStage.PROGRESS;
        if (!rule.wholeAction()
                && (unfinished
                || (rule.multiStage()
                && stage == SemanticFact.ProcessStage.FINAL))) {
            add(fields, claimed, "stage", SemanticValue.ofSymbol(stage.name()));
        }
        if (Boolean.FALSE.equals(fact.completion())
                || (rule.multiStage()
                && Boolean.TRUE.equals(fact.completion()))) {
            add(
                    fields,
                    claimed,
                    "complete",
                    SemanticValue.ofBoolean(fact.completion())
            );
        }
    }

    private static String unitName(String unit) {
        return switch (unit) {
            case "CREDITS" -> "credits";
            case "UNITS" -> "units";
            case "LIGHT_YEARS" -> "distanceLy";
            case "TONNES" -> "tonnes";
            case "FRACTION" -> "fraction";
            case "MULTIPLIER" -> "multiplier";
            default -> null;
        };
    }

    private static void add(
            List<LlmDecisionRequest.Field> fields,
            Set<String> claimed,
            String name,
            SemanticValue value
    ) {
        if (value == null || !value.known() || !claimed.add(name)) {
            return;
        }
        fields.add(new LlmDecisionRequest.Field(name, value));
    }

    private static JournalEventObservation payloadOf(
            ProjectedObservation projected
    ) {
        return projected.trigger().payload()
                instanceof JournalEventObservation event
                ? event
                : null;
    }

    /**
     * One projected event with what the rest of the turn needs to know.
     *
     * <p>The mechanism is the family of game thing this is; the context profile
     * is how much of the situation it is read against, already resolved from
     * the rule so nothing downstream has to ask the mechanism for it. The bus
     * sequence is the internal identity the local id stands for and never
     * leaves Kairon.</p>
     */
    public record ProjectedEvent(
            LlmDecisionRequest.Event event,
            DecisionMechanism mechanism,
            DecisionContextProfile contextProfile,
            long busSequence,
            Map<String, SemanticValue> statedFacts
    ) {

        public ProjectedEvent(
                LlmDecisionRequest.Event event,
                DecisionMechanism mechanism,
                DecisionContextProfile contextProfile,
                long busSequence
        ) {
            this(
                    event,
                    mechanism,
                    contextProfile,
                    busSequence,
                    statedFactsOf(event)
            );
        }

        public ProjectedEvent {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(mechanism, "mechanism");
            Objects.requireNonNull(contextProfile, "contextProfile");
            statedFacts = Map.copyOf(
                    Objects.requireNonNull(statedFacts, "statedFacts")
            );
        }

        /**
         * Whether this event already states this canonical field's value.
         *
         * <p>Both halves have to match. A value alone proved nothing: a landing
         * that says it is the first one at this body carries
         * {@code occurrenceOnBody: 1}, and that used to count as having stated
         * every canonical field whose value happened to be {@code 1} — so one
         * biological signal was suppressed and two were not, and the section a
         * fact appeared in depended on an unrelated integer.</p>
         *
         * <p>The identity compared is the canonical field's own model-facing
         * name, resolved through {@link DecisionNames}. That is the identity the
         * request already uses: a name means the same thing wherever it appears
         * in one document, so an event emitting {@code planetClass} states
         * {@link SemanticField#PLANET_CLASS} and nothing else. Where an event
         * answers a canonical slot under a different word, the pairing is
         * declared in {@code DecisionNames} rather than guessed.</p>
         *
         * <p>There is no compound field left to unpack. A scanner reading used
         * to arrive as one {@code signals} set whose categories had to be
         * <em>declared</em> to count canonical fields before this could see
         * them; it now arrives as one count per category, named exactly as the
         * context names it, and the match above is the ordinary one. The
         * declaration went with the shape that needed it.</p>
         */
        public boolean states(SemanticField field, SemanticValue value) {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(value, "value");
            String name = DecisionNames.field(field);
            if (name == null) {
                return false;
            }
            String slot = DecisionNames.slotOf(field);
            return value.equals(statedFacts.get(slot))
                    || value.equals(statedFacts.get(name));
        }

        /**
         * What this event says, keyed by the canonical identity it says it
         * under.
         *
         * <p>Built from the fields the projection actually emitted, at the
         * moment they became model-facing: never from serialized JSON, and never
         * from canonical facts the sparse projection dropped. A field declared
         * to answer a canonical slot is keyed by that slot; everything else is
         * keyed by its own model-facing name.</p>
         *
         * <p>A reported signal set expands into the canonical counts its
         * categories are declared to carry, and only for the categories the set
         * actually contains. A category the reading omitted states nothing —
         * absence is not a count of zero, and inventing one here would put back
         * the very claim the projector stopped making.</p>
         */
        private static Map<String, SemanticValue> statedFactsOf(
                LlmDecisionRequest.Event event
        ) {
            Map<String, SemanticValue> stated = new LinkedHashMap<>();
            for (LlmDecisionRequest.Field field : event.fields()) {
                String slot = DecisionNames.contextSlotStatedBy(field.name());
                stated.putIfAbsent(
                        slot == null ? field.name() : slot,
                        field.value()
                );
            }
            return stated;
        }

    }
}
