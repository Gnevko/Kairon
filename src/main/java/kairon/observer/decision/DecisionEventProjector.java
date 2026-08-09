package kairon.observer.decision;

import kairon.bio.OrganicConditions;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.projection.ProjectedObservation;
import kairon.semantics.SemanticFact;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticObservationEnvelope;
import kairon.semantics.SemanticValue;
import kairon.system.PlanetBody;
import kairon.system.SystemObject;
import kairon.semantics.UnresolvedFact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

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
     * for, in the context. {@code distanceFromArrivalLs} is eleven significant
     * figures of light seconds; it is withdrawn from both halves of the document
     * at once, because a field the context stops sending and an event keeps
     * sending is the same fact under two rules.</p>
     */
    private static final Set<String> DROPPED_QUALIFIERS = Set.of(
            "position",
            "marketId",
            "subCategory",
            "bodyType",
            "distanceFromArrivalLs"
    );

    /**
     * Attributes whose adapter name reads as a predicate rather than a fact.
     *
     * <p>{@code playerControlled} is renamed for a second reason: there is no
     * player in the world the model speaks about. The journal's word is the
     * game's, and the one the document uses everywhere else — actor, context
     * group, the role itself — is the Commander.</p>
     */
    private static final Map<String, String> RENAMED_QUALIFIERS = Map.of(
            "isNewEntry", "newEntry",
            "isPlayer", "player",
            "playerControlled", "commanderControlled"
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

    /** The adapter's identity for the organism, which names it and is not it. */
    private static final String VARIANT_IDENTIFIER = "variantIdentifier";

    /** The same, one level up: the species is what the game prices. */
    private static final String SPECIES_IDENTIFIER = "speciesIdentifier";

    /** Base plus a 400% first-discovery bonus; see {@link #appendSampleValue}. */
    private static final long FIRST_FOOTFALL_MULTIPLE = 5L;

    private final DecisionOrganicNames organicNames;

    public DecisionEventProjector(DecisionOrganicNames organicNames) {
        this.organicNames = Objects.requireNonNull(organicNames, "organicNames");
    }

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
        appendSampleValue(fields, claimed, rule, projected, envelope);
        appendPredictedFloor(fields, claimed, rule, projected, envelope);
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
        return ProjectedEvent.of(
                new LlmDecisionRequest.Event(
                        localId,
                        rule.kind(),
                        description,
                        List.copyOf(fields),
                        organismsFound(rule, projected, envelope)
                ),
                rule,
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
     * The organisms this reading named, or nothing.
     *
     * <p>Only the instrument that names them lists them — see
     * {@link DecisionEventRule#namesOrganisms()}. The names come from the body
     * in the registry snapshot captured with this observation, which is the
     * snapshot this reading has just been folded into: no late read, and no
     * second parse of the record downstream of the adapter that already read
     * it.</p>
     *
     * <p>The body is the one the reading itself identified, not the one the ship
     * happens to be at. A surface scanner fires probes at a body the canonical
     * state need not have selected, and attaching another body's organisms to it
     * would be the same defect the codex entry's contradicted {@code BodyID}
     * caused.</p>
     *
     * <p>Named by the word in the genus identity, exactly as
     * {@code context.biology} names it, so one organism has one spelling
     * wherever the document mentions it. A genus the game has no word for is
     * left out of both.</p>
     */
    /**
     * What the finished sample pays, and why it pays that (ADR-0029).
     *
     * <p>Two facts and one rule. The registry prices a sample of this species;
     * the game states, on the scan that found the body, whether anybody had
     * ever walked on it. Nobody having walked on a body is nobody having
     * sampled anything there, so the data is undiscovered and Vista Genomics
     * pays five times over — base plus a 400% first-discovery bonus, which is
     * exactly what all 61 recorded sales in this project's journals show.</p>
     *
     * <p>{@code valueMCr} is therefore the payout and not the base, in millions
     * rather than in credits: one number in the document, so there is no
     * arithmetic for the model to get wrong, and it is already in the shape it
     * will be said out loud. {@code firstFootfall} beside it says why it is
     * large. Where the game
     * never said whether the body had been walked on, the base price is sent
     * without the flag — that is the price the game publishes and it is true
     * whatever the bonus does; claiming the bonus needs evidence, and silence
     * about footfall is not it. Absent entirely with no registry and for an
     * organism the registry does not price.</p>
     *
     * <p><strong>This is a claim, and it is the only predicted one in the
     * document.</strong> The bonus is decided at the sale, not here; what is
     * known here is that nobody had landed. Another Commander landing and
     * selling between this scan and this sale would take it, and Kairon would
     * have said five times.</p>
     */
    private void appendSampleValue(
            List<LlmDecisionRequest.Field> fields,
            Set<String> claimed,
            DecisionEventRule rule,
            ProjectedObservation projected,
            SemanticObservationEnvelope envelope
    ) {
        if (!rule.reportsSampleValue()) {
            return;
        }
        OptionalLong value = OptionalLong.empty();
        for (SemanticFact fact : envelope.structuredFacts()) {
            value = organicNames.sampleValueCr(
                    fact.qualifiers().get(SPECIES_IDENTIFIER)
            );
            if (value.isPresent()) {
                break;
            }
        }
        if (value.isEmpty()) {
            return;
        }
        SystemObject body = bodyOf(projected, envelope);
        boolean first = body != null
                && Boolean.FALSE.equals(body.profile().wasFootfalled());
        long payout = first
                ? value.orElseThrow() * FIRST_FOOTFALL_MULTIPLE
                : value.orElseThrow();
        // Finishing the body replaces the sample's own price with the body's
        // total. One money figure per turn, always: two would be the arithmetic
        // this field exists to keep out of the model's hands, and the last
        // sample's price is inside the total anyway.
        OptionalLong total = bodyTotal(body, first);
        add(
                fields,
                claimed,
                total.isPresent() ? "bodyTotalMCr" : "valueMCr",
                new SemanticValue.DecimalValue(
                        millions(total.orElse(payout))
                )
        );
        if (first) {
            add(
                    fields,
                    claimed,
                    "firstFootfall",
                    new SemanticValue.BooleanValue(true)
            );
        }
    }

    /**
     * The least this body's organisms could be worth, on the turn they are named.
     *
     * <p>The scan names genera and the game prices species, so the scan alone is
     * not a number. This narrows each genus by what the body's own scan reported
     * — planet class, atmosphere, gravity, temperature, pressure, volcanism —
     * and sums the cheapest survivor of each (ADR-0030). The same footfall
     * evidence multiplies it by the same five.</p>
     *
     * <p>Rides on {@link DecisionEventRule#namesOrganisms()} rather than on a
     * declaration of its own: the genus list is exactly what this sums over, and
     * a second flag that would have to agree with the first is a way for them to
     * disagree.</p>
     *
     * <p><strong>It is a floor, and the name says so.</strong> Every decision
     * underneath leans the same way — an unreported condition admits, an
     * unstated ruleset admits, a genus that survives nothing drops out of the
     * sum — so the answer understates. That is the only direction it is allowed
     * to be wrong in: this is the one thing in the request Kairon infers rather
     * than reads.</p>
     */
    private void appendPredictedFloor(
            List<LlmDecisionRequest.Field> fields,
            Set<String> claimed,
            DecisionEventRule rule,
            ProjectedObservation projected,
            SemanticObservationEnvelope envelope
    ) {
        if (!rule.namesOrganisms()) {
            return;
        }
        SystemObject body = bodyOf(projected, envelope);
        if (!(body instanceof PlanetBody planet)) {
            return;
        }
        OptionalLong floor = organicNames.floorValueCr(
                body.biology().genera().keySet(),
                OrganicConditions.ofScan(
                        planet.planetClass(),
                        planet.atmosphereType(),
                        planet.volcanism(),
                        planet.surfaceGravity(),
                        planet.surfaceTemperature(),
                        planet.surfacePressure()
                )
        );
        if (floor.isEmpty()) {
            return;
        }
        boolean first = Boolean.FALSE.equals(body.profile().wasFootfalled());
        add(
                fields,
                claimed,
                "atLeastMCr",
                new SemanticValue.DecimalValue(millions(
                        first
                                ? floor.orElseThrow() * FIRST_FOOTFALL_MULTIPLE
                                : floor.orElseThrow()
                ))
        );
        if (first) {
            add(
                    fields,
                    claimed,
                    "firstFootfall",
                    new SemanticValue.BooleanValue(true)
            );
        }
    }

    /**
     * What the whole body paid, once every genus on it is collected.
     *
     * <p>Empty until then, and empty when the survey named nothing: a body
     * nobody mapped has no list to have finished. Summed over the species
     * actually collected, because the game prices a species and the genus
     * inventory cannot answer — one genus can be several species.</p>
     */
    private OptionalLong bodyTotal(SystemObject body, boolean firstFootfall) {
        if (body == null || !body.biology().allCollected()) {
            return OptionalLong.empty();
        }
        long total = 0L;
        for (String species : body.biology().collectedSpecies()) {
            OptionalLong value = organicNames.sampleValueCr(
                    SemanticValue.ofSymbol(species)
            );
            if (value.isEmpty()) {
                // One unpriced organism makes the total a lie about the rest.
                return OptionalLong.empty();
            }
            total += value.orElseThrow();
        }
        return total == 0L
                ? OptionalLong.empty()
                : OptionalLong.of(
                        firstFootfall ? total * FIRST_FOOTFALL_MULTIPLE : total
                );
    }

    /**
     * A payout in millions, to one decimal place.
     *
     * <p>Credits are how the game states a price and millions are how anybody
     * says one out loud: {@code 12934900} is seven digits to read and "12.9" is
     * two syllables. The whole point of this field is that it gets spoken, so
     * it is sent in the shape it will be spoken in rather than in the shape it
     * was stored in.</p>
     *
     * <p>One decimal is enough for every organism in the registry and for every
     * bonus on one: the cheapest is 0.1 and the dearest, at five times over, is
     * 100.0. It is deliberately not exact — the exact figure is the game's to
     * state at the sale, and a companion saying "twelve point nine three four
     * nine million" is reading out a database.</p>
     */
    private static double millions(long credits) {
        return Math.round(credits / 100_000.0) / 10.0;
    }

    /**
     * The body this observation is about, as the registry holds it.
     *
     * <p>Read off the fact's own identity rather than off canonical state: a
     * sampling record files its body under {@code Body}, and the registry
     * snapshot captured with this observation is the one that answers for
     * it.</p>
     */
    private static SystemObject bodyOf(
            ProjectedObservation projected,
            SemanticObservationEnvelope envelope
    ) {
        for (SemanticFact fact : envelope.structuredFacts()) {
            if (!(fact.identity()
                    instanceof SemanticValue.IdentityValue identity)) {
                continue;
            }
            try {
                SystemObject body = projected.systemRegistry()
                        .object(Long.parseLong(identity.value()));
                if (body != null) {
                    return body;
                }
            } catch (NumberFormatException notABodyId) {
                // Not a body identity; the next fact may carry one.
            }
        }
        return null;
    }

    private List<LlmDecisionRequest.Listing> organismsFound(
            DecisionEventRule rule,
            ProjectedObservation projected,
            SemanticObservationEnvelope envelope
    ) {
        if (!rule.namesOrganisms()) {
            return List.of();
        }
        SystemObject body = bodyOf(projected, envelope);
        if (body == null) {
            return List.of();
        }
        Set<String> named = new TreeSet<>();
        body.biology().genera().forEach((identity, label) -> {
            String name = organicNames.name(identity, label);
            if (name != null) {
                named.add(name);
            }
        });
        return named.isEmpty()
                ? List.of()
                : List.of(new LlmDecisionRequest.Listing(
                        "organisms",
                        List.copyOf(named)
                ));
    }

    private void appendFact(
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
        if (object == null || object.name() == null || rule.unnamedObject()) {
            // The adapter's name for it is not a name; see
            // DecisionEventRule.unnamedObject.
            return;
        }
        String name = rule.objectName() != null
                ? rule.objectName()
                : DecisionNames.entity(object.kind());
        if (name == null || answeredByContext(name, rule)) {
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
     * Whether the situation already answers this name, so the event need not.
     *
     * <p>Two conditions, and both are necessary. The mechanism must be one that
     * {@linkplain DecisionMechanism#locatedByCanonicalState() leaves the place
     * to canonical state} — an arrival, a landing, a change of vessel — and the
     * event's own context profile must ask for the subject that then answers
     * it. The second half is what makes this a move rather than a deletion: a
     * disembark is read against the Commander, the vehicle and the body, but
     * not against the system, so it goes on naming the system itself.</p>
     *
     * <p>Applied to the object and to the qualifiers alike, because which of
     * the two carries the place is the adapter's business and not a claim about
     * the event: an approach names its body as the thing acted on, a landing
     * names it beside the ship.</p>
     */
    private static boolean answeredByContext(
            String name,
            DecisionEventRule rule
    ) {
        if (!rule.mechanism().locatedByCanonicalState()) {
            return false;
        }
        return switch (name) {
            case "body" -> rule.contextProfile().asksAboutABody();
            case "system" -> rule.contextProfile()
                    .contextNeeds()
                    .contains(DecisionContextProfile.ContextNeed.SYSTEM);
            default -> false;
        };
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
        if (answeredByContext(key, rule)) {
            return true;
        }
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

    private void appendQualifiers(
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
                if (SAMPLING_TAXON_LEVELS.contains(key)
                        || VARIANT_IDENTIFIER.equals(key)
                        || SPECIES_IDENTIFIER.equals(key)) {
                    continue;
                }
                if ("variant".equals(key)) {
                    // The registry names it; the journal's own rendering is the
                    // fallback, not the value. See DecisionOrganicNames.
                    add(
                            fields,
                            claimed,
                            "organism",
                            organicNames.name(
                                    fact.qualifiers().get(VARIANT_IDENTIFIER),
                                    entry.getValue()
                            )
                    );
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
            Map<String, SemanticValue> statedFacts,
            Map<String, SemanticValue> kindStatements
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
                    statedFactsOf(event),
                    Map.of()
            );
        }

        public ProjectedEvent {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(mechanism, "mechanism");
            Objects.requireNonNull(contextProfile, "contextProfile");
            statedFacts = Map.copyOf(
                    Objects.requireNonNull(statedFacts, "statedFacts")
            );
            kindStatements = Map.copyOf(
                    Objects.requireNonNull(kindStatements, "kindStatements")
            );
        }

        /**
         * The projected event a rule produces, with everything the rule claims.
         *
         * <p>Two kinds of claim end up here and they are kept apart. What the
         * event's own fields say is read off the fields; what the event's
         * <em>sentence</em> says is declared on the rule, because no field
         * carries it — "a ship entered supercruise" states the flight mode in
         * words and emits no flight-mode field at all.</p>
         */
        static ProjectedEvent of(
                LlmDecisionRequest.Event event,
                DecisionEventRule rule,
                long busSequence
        ) {
            Objects.requireNonNull(rule, "rule");
            Map<String, SemanticValue> declared = new LinkedHashMap<>();
            rule.statedValues().forEach((field, value) -> {
                String slot = DecisionNames.slotOf(field);
                if (slot != null) {
                    declared.put(slot, value);
                }
            });
            return new ProjectedEvent(
                    event,
                    rule.mechanism(),
                    rule.contextProfile(),
                    busSequence,
                    statedFactsOf(event),
                    declared
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
                    || value.equals(statedFacts.get(name))
                    || value.equals(kindStatements.get(slot));
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
