package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticValue;
import kairon.state.CurrentGameStateSemantics;
import kairon.state.CurrentGameStateSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contracts that span layers, stated once.
 *
 * <p>Each of these says something no single layer can say on its own: that an
 * occurrence and the event the model was shown are the same finding, that a
 * suppressed duplicate cost nothing anywhere, that a value the model was told is
 * still true. Nothing here is a general-purpose truth: each assertion names the
 * observation it is about and reports the whole {@link PipelineTrace} when it
 * fails, because a cross-layer failure is unreadable without the other
 * layers.</p>
 *
 * <p>Deliberately several small assertions rather than one large one. A single
 * "everything is consistent" check would report the first thing it noticed and
 * hide the rest, and the shape of a defect is exactly which of these fails.</p>
 */
final class SemanticPipelineAssertions {

    private SemanticPipelineAssertions() {
    }

    /**
     * An observation that restored state and did nothing else.
     *
     * <p>A restoring {@code Location} is the case this exists for. The
     * Commander is already here: canonical state may learn a great deal, but
     * nothing happened, so there is no occurrence to record, no cursor to move,
     * no transition to learn and nothing for the model to be told.</p>
     *
     * <p>What this deliberately does <em>not</em> assert: that the restore's
     * semantic effects are absent from later turns. The architecture audit
     * proved that is not yet guaranteed, and asserting it here would either fail
     * for the wrong reason or quietly encode the current behaviour as correct.
     * It is stated as a target contract instead.</p>
     */
    static void assertRestoreOnly(PipelineTrace trace, long busSequence) {
        PipelineTrace.ObservationRecord record = trace.observation(busSequence);
        assertTrue(
                record.occurrenceId().isEmpty(),
                () -> "restore observation #" + busSequence + " ("
                        + record.rawObservationType()
                        + ") minted a structural occurrence\n"
                        + trace.describe()
        );
        assertTrue(
                trace.occurrenceOf(busSequence).isEmpty(),
                () -> "restore observation #" + busSequence
                        + " owns an occurrence in the episode timeline\n"
                        + trace.describe()
        );
        assertTrue(
                trace.turnCarrying(busSequence).isEmpty(),
                () -> "restore observation #" + busSequence
                        + " became a model trigger\n" + trace.describe()
        );
        for (PipelineTrace.TurnView turn : trace.turns()) {
            assertFalse(
                    turn.triggerBusSequences().contains(busSequence),
                    () -> "restore observation #" + busSequence
                            + " reached turn " + turn.turnSequence() + "\n"
                            + trace.describe()
            );
        }
    }

    /**
     * A structural event the model was told about, on both sides.
     *
     * <p>Only for kinds whose contract really is structural <em>and</em>
     * trigger-eligible. A conversational event such as {@code MESSAGE_RECEIVED}
     * legitimately has no occurrence, and a structural but model-silent event
     * such as {@code StartJump} legitimately opens no turn; applying this to
     * either would assert a contract neither has.</p>
     */
    static void assertNewStructuralTrigger(
            PipelineTrace trace,
            long busSequence,
            NormalizedEventType expectedType,
            String expectedKind
    ) {
        Objects.requireNonNull(expectedType, "expectedType");
        Objects.requireNonNull(expectedKind, "expectedKind");
        PipelineTrace.ObservationRecord record = trace.observation(busSequence);
        if (trace.graphEnabled()) {
            PipelineTrace.OccurrenceView occurrence =
                    trace.occurrenceOf(busSequence).orElseGet(() -> {
                        fail("observation #" + busSequence + " ("
                                + record.rawObservationType()
                                + ") minted no occurrence\n"
                                + trace.describe());
                        return null;
                    });
            assertEquals(
                    expectedType,
                    occurrence.eventType(),
                    () -> "occurrence type for #" + busSequence + "\n"
                            + trace.describe()
            );
            assertEquals(
                    Optional.of(busSequence),
                    occurrence.sourceBusSequence(),
                    () -> "occurrence for #" + busSequence
                            + " belongs to another observation\n"
                            + trace.describe()
            );
        }
        PipelineTrace.TurnView turn = trace.turnCarrying(busSequence)
                .orElseGet(() -> {
                    fail("observation #" + busSequence + " ("
                            + record.rawObservationType()
                            + ") opened no turn\n" + trace.describe());
                    return null;
                });
        int position = turn.triggerBusSequences().indexOf(busSequence);
        assertTrue(
                position >= 0 && position < turn.eventIds().size(),
                () -> "trigger #" + busSequence
                        + " has no event in its own turn\n" + trace.describe()
        );
        assertEquals(
                expectedKind,
                turn.eventKinds().get(position),
                () -> "model-facing kind for #" + busSequence + "\n"
                        + trace.describe()
        );
        assertEquals(
                position + 1,
                turn.eventIds().get(position),
                () -> "local event ids must be 1..n in source order\n"
                        + trace.describe()
        );
        if (trace.graphEnabled() && turn.hasTrajectory()) {
            long earlierOfType = trace.occurrences().stream()
                    .filter(occurrence ->
                            occurrence.eventType().equals(expectedType))
                    .filter(occurrence -> occurrence.sourceBusSequence()
                            .map(sequence -> sequence < busSequence)
                            .orElse(true))
                    .count();
            long namedInRecent = countOf(turn.recent(), expectedKind);
            assertTrue(
                    namedInRecent <= earlierOfType,
                    () -> "trajectory.recent names " + expectedKind + " "
                            + namedInRecent + " times but only "
                            + earlierOfType
                            + " earlier occurrence(s) exist, so the current "
                            + "one is in its own history\n" + trace.describe()
            );
        }
    }

    /**
     * A repeat that cost nothing anywhere.
     *
     * <p>Compared against a snapshot taken before the duplicate arrived, so it
     * states what the duplicate did rather than what the run looks like.
     * Canonical state is deliberately excluded: a duplicate reading still
     * refreshes what is known, and that is not an error.</p>
     */
    static void assertDuplicateSuppressed(
            PipelineTrace before,
            PipelineTrace after
    ) {
        assertEquals(
                before.occurrences().size(),
                after.occurrences().size(),
                () -> "a duplicate created an occurrence\n" + after.describe()
        );
        assertEquals(
                totalTransitions(before),
                totalTransitions(after),
                () -> "a duplicate created a transition\n" + after.describe()
        );
        assertEquals(
                before.cursor().map(PipelineTrace.CursorView::occurrenceId),
                after.cursor().map(PipelineTrace.CursorView::occurrenceId),
                () -> "a duplicate moved the cursor\n" + after.describe()
        );
        assertEquals(
                before.providerCalls(),
                after.providerCalls(),
                () -> "a duplicate opened a turn\n" + after.describe()
        );
        assertEquals(
                before.turns().isEmpty()
                        ? null
                        : before.turns().getLast().userMessage(),
                after.turns().isEmpty()
                        ? null
                        : after.turns().getLast().userMessage(),
                () -> "a duplicate changed what the model was last told\n"
                        + after.describe()
        );
    }

    /** Nothing was asked of the provider between two points in the run. */
    static void assertNoProviderTurn(PipelineTrace before, PipelineTrace after) {
        assertEquals(
                before.providerCalls(),
                after.providerCalls(),
                () -> "the provider was called\n" + after.describe()
        );
        for (PipelineTrace.TurnView turn : after.turns()) {
            assertFalse(
                    turn.eventKinds().isEmpty(),
                    () -> "an empty request reached the provider\n"
                            + after.describe()
            );
        }
    }

    /**
     * One finding, one occurrence, one event, one observation.
     *
     * <p>The bijection is the point. A model-facing event whose occurrence
     * belongs to a different observation — a historical one, or an earlier
     * reading of the same body — is the shape of the defect this project fixed
     * twice, and it is invisible to any single-layer test.</p>
     */
    static void assertOccurrenceAndEventAgree(
            PipelineTrace trace,
            long busSequence,
            NormalizedEventType expectedType
    ) {
        if (!trace.graphEnabled()) {
            fail("occurrence agreement cannot be asserted without a graph\n"
                    + trace.describe());
        }
        List<PipelineTrace.OccurrenceView> owned = trace.occurrences().stream()
                .filter(occurrence -> occurrence.sourceBusSequence()
                        .filter(sequence -> sequence == busSequence)
                        .isPresent())
                .toList();
        assertEquals(
                1,
                owned.size(),
                () -> "observation #" + busSequence
                        + " owns " + owned.size() + " occurrences\n"
                        + trace.describe()
        );
        assertEquals(
                expectedType,
                owned.getFirst().eventType(),
                () -> "occurrence type\n" + trace.describe()
        );
        PipelineTrace.TurnView turn = trace.turnCarrying(busSequence)
                .orElseGet(() -> {
                    fail("observation #" + busSequence
                            + " has an occurrence but reached no turn\n"
                            + trace.describe());
                    return null;
                });
        int position = turn.triggerBusSequences().indexOf(busSequence);
        // Both sides say the same sentence, because the trajectory says the
        // same sentences the events do. It used to be two internal names —
        // the trajectory's identifier against the catalogue kind — which
        // agreed with each other and with nothing the model could read.
        assertEquals(
                DecisionTrajectoryDescriptions.descriptionOf(expectedType),
                turn.eventDescriptions().get(position),
                () -> "the occurrence and the model-facing event are described "
                        + "differently\n" + trace.describe()
        );
    }

    /**
     * Every background change still describes the world as it now is.
     *
     * <p>A change with no {@code eventId} is Kairon volunteering something no
     * event of the request caused. If what it says has since been replaced, the
     * document's only statement about that field is the wrong one — and, because
     * a change registers its field as already stated, it also displaces the
     * correct value from the context.</p>
     *
     * <p>Asserted on the request as an object, before serialization. The
     * attribution this contract turns on is {@code eventId}, which is internal
     * and never sent; reading it back out of the document is impossible by
     * construction, and keeping it on the wire so that a test could read it
     * would be shaping the provider contract around the test. The object is the
     * one that was sent — {@code SemanticPipelineHarness} proves that by
     * re-serializing it — so nothing is asserted against a lookalike.</p>
     *
     * <p>Both sides are typed {@link SemanticValue}: the final canonical value
     * is read through {@link CurrentGameStateSemantics}, the same reader the
     * projector uses, and compared by variant. Not by rendered text.</p>
     */
    static void assertNoStaleChanges(PipelineTrace trace) {
        CurrentGameStateSnapshot finalState = trace.finalState().orElseThrow(
                () -> new AssertionError(
                        "no canonical state was captured\n" + trace.describe()
                )
        );
        for (PipelineTrace.TurnView turn : trace.turns()) {
            for (LlmDecisionRequest.Change change
                    : turn.request().changes()) {
                if (change.eventId() != null) {
                    continue;
                }
                for (LlmDecisionRequest.FieldChange field : change.fields()) {
                    SemanticField canonical =
                            fieldFor(change.subject(), field.name());
                    if (canonical == null) {
                        continue;
                    }
                    SemanticValue current = CurrentGameStateSemantics.valueOf(
                            canonical,
                            finalState
                    );
                    assertTrue(
                            matches(current, field.after()),
                            () -> "background change " + change.subject() + "."
                                    + field.name() + " says " + field.after()
                                    + " but the final canonical value is "
                                    + current + "\n" + trace.describe()
                    );
                }
            }
        }
    }

    /**
     * Attribution is decided, and it never reaches the provider.
     *
     * <p>The other half of the contract above. A change is attributed to one of
     * this request's own events or to nothing at all, and the position it names
     * must be a position the request actually has — a pointer past the end of
     * {@code events} would make the reconciliation above skip a change it
     * should have checked. None of it is serialized.</p>
     */
    static void assertChangeAttributionStaysInternal(PipelineTrace trace) {
        for (PipelineTrace.TurnView turn : trace.turns()) {
            int events = turn.request().events().size();
            for (LlmDecisionRequest.Change change
                    : turn.request().changes()) {
                Integer eventId = change.eventId();
                if (eventId != null) {
                    assertTrue(
                            eventId >= 1 && eventId <= events,
                            () -> "a change is attributed to event " + eventId
                                    + ", which this request does not have\n"
                                    + trace.describe()
                    );
                }
            }
            assertFalse(
                    turn.userMessage().contains("\"eventId\""),
                    () -> "the request identifies a change's cause to the "
                            + "model\n" + trace.describe()
            );
            assertFalse(
                    turn.userMessage().contains("\"id\""),
                    () -> "the request identifies an event to the model\n"
                            + trace.describe()
            );
        }
    }

    /**
     * A fact nothing established is absent everywhere.
     *
     * <p>Absence is how this contract says "unknown". A zero, a {@code false}
     * or an empty string in its place is a different claim, and one no source
     * made.</p>
     */
    static void assertUnknownNotMaterialized(
            PipelineTrace trace,
            SemanticField field
    ) {
        CurrentGameStateSnapshot finalState = trace.finalState().orElseThrow();
        SemanticValue current =
                CurrentGameStateSemantics.valueOf(field, finalState);
        assertFalse(
                current.known(),
                () -> field + " is known (" + current
                        + "), so this assertion is about the wrong field\n"
                        + trace.describe()
        );
        String subject = DecisionNames.subject(field.subject());
        String name = DecisionNames.field(field);
        if (name == null) {
            return;
        }
        for (PipelineTrace.TurnView turn : trace.turns()) {
            for (JsonNode change : turn.changes()) {
                if (!subject.equals(change.path("subject").textValue())) {
                    continue;
                }
                assertFalse(
                        change.path("fields").has(name),
                        () -> "unknown " + field + " was reported as a change\n"
                                + trace.describe()
                );
            }
            JsonNode group = turn.context().path(subject);
            assertFalse(
                    group.has(name),
                    () -> "unknown " + field + " was reported as context\n"
                            + trace.describe()
            );
        }
    }

    /**
     * The journal's order, all the way to the serialized request.
     *
     * <p>Checked on bus sequences and array positions, never on timestamps: two
     * records of one action share a timestamp, and the order they were written
     * in is the only thing that says which came first.</p>
     */
    static void assertSourceOrder(PipelineTrace trace) {
        long previous = 0L;
        for (PipelineTrace.ObservationRecord record : trace.observations()) {
            long current = record.busSequence();
            assertTrue(
                    current > previous,
                    () -> "observations are out of bus order\n"
                            + trace.describe()
            );
            previous = current;
        }
        for (PipelineTrace.TurnView turn : trace.turns()) {
            List<Long> triggers = turn.triggerBusSequences();
            for (int index = 1; index < triggers.size(); index++) {
                int position = index;
                assertTrue(
                        triggers.get(position) > triggers.get(position - 1),
                        () -> "turn " + turn.turnSequence()
                                + " reordered its triggers\n"
                                + trace.describe()
                );
            }
            List<Integer> ids = turn.eventIds();
            for (int index = 0; index < ids.size(); index++) {
                int position = index;
                assertEquals(
                        position + 1,
                        ids.get(position),
                        () -> "local event ids must be 1..n in order\n"
                                + trace.describe()
                );
            }
            if (!triggers.isEmpty()) {
                assertEquals(
                        triggers.size(),
                        ids.size(),
                        () -> "every trigger must have exactly one event\n"
                                + trace.describe()
                );
            }
        }
        if (trace.graphEnabled()) {
            for (PipelineTrace.EpisodeView episode : trace.episodes()) {
                long previousSequence = -1L;
                for (PipelineTrace.OccurrenceView occurrence
                        : episode.occurrences()) {
                    long sequence = occurrence.episodeSequence();
                    assertTrue(
                            sequence > previousSequence,
                            () -> "occurrences are out of episode order\n"
                                    + trace.describe()
                    );
                    previousSequence = sequence;
                }
            }
        }
    }

    /**
     * A field is stated once, in one section, and the two agree.
     *
     * <p>{@code changes} is what just happened; {@code context} is what is
     * standing. A field in both invites the model to count it twice, and a field
     * whose section depends on anything other than causality makes the sections
     * meaningless.</p>
     */
    static void assertChangesAndContextPartition(PipelineTrace trace) {
        for (PipelineTrace.TurnView turn : trace.turns()) {
            Map<String, JsonNode> changed = new LinkedHashMap<>();
            for (JsonNode change : turn.changes()) {
                String subject = change.path("subject").textValue();
                JsonNode fields = change.path("fields");
                fields.fieldNames().forEachRemaining(name -> changed.put(
                        subject + "." + name,
                        fields.path(name).path("after")
                ));
            }
            JsonNode context = turn.context();
            context.fieldNames().forEachRemaining(group -> {
                JsonNode facts = context.path(group);
                facts.fieldNames().forEachRemaining(name -> {
                    String slot = group + "." + name;
                    assertFalse(
                            changed.containsKey(slot),
                            () -> slot + " is stated in both changes and "
                                    + "context\n" + trace.describe()
                    );
                });
            });
        }
    }

    /**
     * The same run, with and without a behaviour graph, says the same things.
     *
     * <p>What the graph adds is history: a trajectory, a prediction and how
     * often something has happened at this body. Everything else — which events
     * the model is shown, what they factually say, what changed, what is
     * standing, and how many times the provider was asked — is the observer's
     * and the projection's, and must not depend on whether the graph is
     * running.</p>
     */
    static void assertGraphDisabledParity(
            PipelineTrace withGraph,
            PipelineTrace withoutGraph
    ) {
        assertTrue(withGraph.graphEnabled(), "first trace must have a graph");
        assertFalse(
                withoutGraph.graphEnabled(),
                "second trace must have no graph"
        );
        assertEquals(
                withGraph.providerCalls(),
                withoutGraph.providerCalls(),
                () -> "provider call count differs\nwith graph:\n"
                        + withGraph.describe() + "\nwithout graph:\n"
                        + withoutGraph.describe()
        );
        assertEquals(
                withGraph.modelFacingKinds(),
                withoutGraph.modelFacingKinds(),
                () -> "model-facing kinds differ\nwith graph:\n"
                        + withGraph.describe() + "\nwithout graph:\n"
                        + withoutGraph.describe()
        );
        for (int index = 0; index < withGraph.turns().size(); index++) {
            int position = index;
            PipelineTrace.TurnView left = withGraph.turns().get(position);
            PipelineTrace.TurnView right = withoutGraph.turns().get(position);
            assertEquals(
                    left.triggerBusSequences(),
                    right.triggerBusSequences(),
                    () -> "trigger admission differs at turn " + position
                            + "\nwith graph:\n" + withGraph.describe()
                            + "\nwithout graph:\n" + withoutGraph.describe()
            );
            assertEquals(
                    withoutGraphShape(left.document()),
                    withoutGraphShape(right.document()),
                    () -> "requests differ beyond graph-derived content at "
                            + "turn " + position + "\n  with graph:    "
                            + left.userMessage() + "\n  without graph: "
                            + right.userMessage()
            );
        }
    }

    /**
     * The request with everything the graph contributes removed.
     *
     * <p>Exactly three things: the trajectory, its predictions, and the
     * per-body occurrence count. Nothing else is normalised away — the point of
     * the comparison is what survives it.</p>
     */
    private static JsonNode withoutGraphShape(JsonNode document) {
        JsonNode copy = document.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy)
                .remove("trajectory");
        copy.path("events").forEach(event -> {
            if (event.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) event)
                        .remove("occurrenceOnBody");
            }
        });
        return copy;
    }

    // ------------------------------------------------------------- internals

    private static long totalTransitions(PipelineTrace trace) {
        return trace.episodes().stream()
                .mapToLong(episode -> episode.transitions().size())
                .sum();
    }

    private static long countOf(List<String> values, String value) {
        return values.stream().filter(value::equals).count();
    }

    /**
     * The canonical field a model-facing {@code subject.name} stands for.
     *
     * <p>Built by asking the production naming table what each field is called,
     * so it cannot drift from what the serializer wrote. A name two fields
     * claim is reported rather than resolved arbitrarily.</p>
     */
    private static SemanticField fieldFor(String subject, String name) {
        List<SemanticField> candidates =
                CANONICAL_NAMES.getOrDefault(subject + "." + name, List.of());
        if (candidates.size() > 1) {
            fail("model-facing name " + subject + "." + name
                    + " is claimed by " + candidates);
        }
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private static final Map<String, List<SemanticField>> CANONICAL_NAMES =
            canonicalNames();

    private static Map<String, List<SemanticField>> canonicalNames() {
        Map<String, List<SemanticField>> byName = new HashMap<>();
        for (SemanticField field : SemanticField.values()) {
            String name = DecisionNames.field(field);
            if (name == null) {
                continue;
            }
            byName.computeIfAbsent(
                    DecisionNames.subject(field.subject()) + "." + name,
                    ignored -> new ArrayList<>()
            ).add(field);
        }
        return Map.copyOf(byName);
    }

    /**
     * Whether a typed canonical value and a serialized value say the same thing.
     *
     * <p>Compared by variant rather than by rendering, so a symbol is not equal
     * to the text that happens to spell it and an integral zero is not equal to
     * a missing field.</p>
     */
    /**
     * Two canonical values agree, compared by variant rather than by identity.
     *
     * <p>Deliberately not {@code equals}: a quantity carries a unit the
     * projection may have normalized, and what this contract is about is
     * whether the model was told the value the world now holds.</p>
     */
    private static boolean matches(SemanticValue current, SemanticValue after) {
        if (!after.known()) {
            return !current.known();
        }
        return switch (current) {
            case SemanticValue.UnknownValue ignored -> false;
            case SemanticValue.TextValue text ->
                    after instanceof SemanticValue.TextValue other
                            && text.value().equals(other.value());
            case SemanticValue.SymbolicValue symbol ->
                    after instanceof SemanticValue.SymbolicValue other
                            && symbol.symbol().equals(other.symbol());
            case SemanticValue.BooleanValue flag ->
                    after instanceof SemanticValue.BooleanValue other
                            && flag.value() == other.value();
            case SemanticValue.IntegralValue integral ->
                    after instanceof SemanticValue.IntegralValue other
                            && integral.value() == other.value();
            case SemanticValue.DecimalValue decimal ->
                    after instanceof SemanticValue.DecimalValue other
                            && Double.compare(
                                    decimal.value(),
                                    other.value()
                            ) == 0;
            // Signal sets and identities are not canonical snapshot fields, so
            // CurrentGameStateSemantics never produces one here.
            default -> true;
        };
    }

}
