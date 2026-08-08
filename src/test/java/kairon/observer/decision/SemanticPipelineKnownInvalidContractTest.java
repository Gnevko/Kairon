package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.semantics.SemanticField;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static kairon.observer.decision.Journal.jump;
import static kairon.observer.decision.Journal.loadGame;
import static kairon.observer.decision.Journal.location;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertDuplicateSuppressed;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertUnknownNotMaterialized;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contracts Kairon does not keep yet.
 *
 * <p>Every test here states what the pipeline <em>should</em> do, on a scenario
 * the architecture audit reproduced. They are not placeholders and they are not
 * descriptions of current behaviour: while one is disabled, enabling it without
 * changing production makes it fail, which is exactly what a target contract is
 * for. A contract that has since been met is enabled and stays here as the
 * regression test for the defect it named.</p>
 *
 * <p>The rule this suite exists to enforce is that a known defect is written
 * down as the contract it breaks rather than as a comment. A disabled test that
 * asserted today's behaviour would be worse than no test: it would certify the
 * defect.</p>
 */
final class SemanticPipelineKnownInvalidContractTest {

    /**
     * B1: what happened before Kairon was listening is not news.
     *
     * <p>Audit reproduction R7. A bootstrap arrival, approach and scanner
     * reading all leave semantic effects in the accumulator; the first live
     * event to close a batch over them presents them as background changes with
     * no {@code eventId}. The Commander is then told that the system became
     * Schieni and the ship became a ship, at the moment they touched down.</p>
     *
     * <p>The cause was structural rather than local: {@code captureMode} was
     * dropped on the way to the effects, so neither
     * {@code SemanticEffectAccumulator} nor {@code DecisionChangeSelector} could
     * tell a historical effect from a live one. It is kept as
     * {@code EffectRetention} on the applied observation, and the accumulator
     * declines a {@code RESTORE_ONLY} envelope outright.</p>
     */
    @Test
    void bootstrapEffectsMustNotBecomeLiveBackgroundChanges(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame("10:00:00Z"))
                    .journal(
                            ObservationCaptureMode.BOOTSTRAP,
                            jump("10:00:01Z", 23155, "Schieni")
                    )
                    .journal(
                            ObservationCaptureMode.BOOTSTRAP,
                            approach("10:00:10Z")
                    )
                    .journal(
                            ObservationCaptureMode.BOOTSTRAP,
                            signals("10:00:20Z", "FSSBodySignals", BIO_1)
                    )
                    .journal(location("10:00:30Z", 23155, "Schieni"))
                    .settleProjection();
            harness.journal(ObservationCaptureMode.LIVE, touchdown("10:01:00Z"))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertEquals(
                    1,
                    trace.providerCalls(),
                    "the landing is the only thing worth a turn: nothing "
                            + "historical opened one\n" + trace.describe()
            );
            PipelineTrace.TurnView turn = trace.turns().getLast();
            assertEquals(
                    List.of("TOUCHDOWN"),
                    turn.eventKinds(),
                    "the live turn is about the landing"
            );
            assertEquals(
                    List.of(),
                    changedSlots(turn),
                    "nothing that happened before Kairon was listening is a "
                            + "change now: " + turn.userMessage()
            );
            assertFalse(
                    changedSlots(turn).contains("system.name"),
                    "the system was already Schieni"
            );
            assertFalse(
                    changedSlots(turn).contains("vehicle.kind"),
                    "the ship was already a ship"
            );
            assertFalse(
                    changedSlots(turn).contains("body.biologicalSignals"),
                    "the reading was taken before Kairon was listening"
            );
            assertFalse(
                    turn.userMessage().contains("biologicalSignals"),
                    "and it is not context either: what a survey found stays "
                            + "on the survey, so a reading taken before Kairon "
                            + "was listening reaches the model nowhere at all: "
                            + turn.userMessage()
            );
        }
    }

    /**
     * B2: a category nothing measured has no count.
     *
     * <p>Audit reproduction R6. A system scan reporting one biological signal
     * says nothing whatever about geology, and
     * {@code CurrentGameStateProjector.updateBodySignals} nevertheless writes a
     * known zero for it, which is then serialized as an established change. A
     * measured zero and an unmeasured category are different facts, and the
     * model cannot tell them apart.</p>
     */
    @Test
    void anUnmeasuredSignalCategoryMustStayUnknown(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrived(harness);
            harness.journal(signals("10:01:00Z", "FSSBodySignals", BIO_1))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertEquals(
                    1,
                    trace.finalBody(23155L, 20L).biologicalSignalCount(),
                    "the reading did establish biology"
            );
            assertNull(
                    trace.finalBody(23155L, 20L).geologicalSignalCount(),
                    "and it established nothing about geology"
            );
            assertUnknownNotMaterialized(
                    trace,
                    SemanticField.GEOLOGICAL_SIGNAL_COUNT
            );
            assertFalse(
                    trace.turns().getLast().userMessage()
                            .contains("geologicalSignals"),
                    "an unmeasured category is absent, not zero: "
                            + trace.turns().getLast().userMessage()
            );
        }
    }

    /**
     * B3: which section a fact lands in is causality, not arithmetic.
     *
     * <p>Audit reproduction R9. {@code ProjectedEvent.states} compares a change
     * to an event's fields by {@code SemanticValue} equality alone, ignoring
     * which field each belongs to. A {@code TOUCHDOWN} carrying
     * {@code occurrenceOnBody: 1} therefore suppresses a change whose value
     * happens to be {@code 1} — so the same established fact is reported as a
     * change when the count is two and as context when the count is one.</p>
     *
     * <p>The target is not that one section is right. It is that the section is
     * decided by what caused the fact, and cannot move because an unrelated
     * number in the same request happens to match.</p>
     *
     * <h2>Which section this scenario now answers</h2>
     * <p>Neither, and that is the third answer this scenario has given. When
     * the contract was written the reading produced a change; the
     * effect-retention phase made a historical reading standing background, so
     * it became context; and since the body group was cut to what a body
     * <em>is</em>, a signal count is not context either. What a survey found is
     * reported by the survey, and a survey taken before Kairon was listening
     * had no turn to report it in.</p>
     *
     * <p>That makes this the weaker end of the pair either way: a fact the
     * request never carries cannot collide with anything. The collision itself
     * is held by {@code FieldAwareStatementTest}, on the predicate. What
     * survives here is the invariant this test is named for: the section does
     * not move with the count.</p>
     */
    @Test
    void aFieldsSectionMustNotDependOnAnUnrelatedEqualValue(
            @TempDir Path directory
    ) {
        String withCollision = sectionOfBiologicalSignals(
                directory.resolve("bio-1"),
                1
        );
        String withoutCollision = sectionOfBiologicalSignals(
                directory.resolve("bio-2"),
                2
        );
        assertEquals(
                withoutCollision,
                withCollision,
                "the same established fact must be presented in the same "
                        + "section whether its count collides with "
                        + "occurrenceOnBody or not"
        );
        assertEquals(
                "neither",
                withCollision,
                "a reading taken before Kairon was listening is neither a "
                        + "change nor context — signal counts stopped riding "
                        + "on the body — and the landing's occurrenceOnBody "
                        + "says nothing about it either way"
        );
    }

    /**
     * B4: one repeated attack, two answers — and no decision yet.
     *
     * <p>Proven on the current branch. Three consecutive {@code UnderAttack}
     * records naming the same target produce <strong>one</strong> graph
     * occurrence — {@code BehaviorOccurrenceProjectionPolicy} lists
     * {@code UNDER_ATTACK} among its repeatable types and suppresses an
     * immediately continuing run — and <strong>three</strong> model turns,
     * because {@code UnderAttack} is {@code NEW_ELIGIBLE},
     * {@code LlmJournalEventSelection.admitsAsTrigger} has no branch for it and
     * {@code BodySurveyNoveltyGuard} admits every non-scanner record.</p>
     *
     * <h2>The open decision</h2>
     * <p><strong>Option A</strong> — a repeat is one event: the observer
     * deduplicates it as the graph does, and the bijection between occurrence
     * and model-facing event holds for every structural kind. Being told twice
     * that the ship is under attack adds nothing the first telling did not.</p>
     *
     * <p><strong>Option B</strong> — a continuing attack is worth repeating: the
     * observer may open a turn per record, and the occurrence/event bijection is
     * explicitly declared not to hold for this kind, which then has to be a
     * named, tested exception rather than an accident of two policies not
     * knowing about each other.</p>
     *
     * <p>This test encodes <strong>Option A</strong> because it is the shape
     * every other structural kind already has. That is a candidate, not a
     * ruling: if Option B is chosen, this body must be replaced by an assertion
     * that the divergence is intended and bounded, not merely enabled.</p>
     */
    @Test
    @Disabled("Open product decision: repeated UNDER_ATTACK is deduplicated by "
            + "the graph and not by the observer. Option A (suppress on both "
            + "sides) is encoded here; Option B (declare the divergence "
            + "intended) would replace it. Not ratified.")
    void aRepeatedAttackMustHaveOneCrossLayerAnswer(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame("10:00:00Z"))
                    .journal(jump("10:00:01Z", 23155, "Schieni"))
                    .closeBatch();
            harness.journal(attack("10:01:00Z")).closeBatch();
            PipelineTrace afterFirst = harness.trace();

            harness.journal(attack("10:01:05Z")).closeBatch();
            harness.journal(attack("10:01:10Z")).closeBatch();
            PipelineTrace afterRepeats = harness.trace();

            assertEquals(
                    1,
                    afterFirst.occurrences().stream()
                            .filter(occurrence -> occurrence.eventType()
                                    .equals(NormalizedEventType.UNDER_ATTACK))
                            .count(),
                    "the first attack is structural"
            );
            assertDuplicateSuppressed(afterFirst, afterRepeats);
            assertEquals(
                    1,
                    afterRepeats.modelFacingKinds().stream()
                            .filter("UNDER_ATTACK"::equals)
                            .count(),
                    "an uninterrupted attack on the same target is one event "
                            + "for the graph, so it is one event for the model"
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private static final String BIO_1 =
            "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}";

    /**
     * Which section of the request states the biological signal count.
     *
     * <p>The whole scenario is identical apart from the reported count, so any
     * difference in the answer is the collision and nothing else.</p>
     */
    private static String sectionOfBiologicalSignals(
            Path directory,
            int count
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame("10:00:00Z"))
                    .journal(
                            ObservationCaptureMode.BOOTSTRAP,
                            jump("10:00:01Z", 23155, "Schieni")
                    )
                    .journal(
                            ObservationCaptureMode.BOOTSTRAP,
                            approach("10:00:10Z")
                    )
                    .journal(
                            ObservationCaptureMode.BOOTSTRAP,
                            signals(
                                    "10:00:20Z",
                                    "FSSBodySignals",
                                    "{\"Type\":\"$SAA_SignalType_Biological;\","
                                            + "\"Count\":" + count + "}"
                            )
                    )
                    .settleProjection();
            harness.journal(ObservationCaptureMode.LIVE, touchdown("10:01:00Z"))
                    .closeBatch();
            PipelineTrace.TurnView turn =
                    harness.trace().turns().getLast();
            assertTrue(
                    turn.eventKinds().contains("TOUCHDOWN"),
                    "the scenario must reach the landing"
            );
            boolean inChanges =
                    changedSlots(turn).contains("body.biologicalSignals");
            boolean inContext = turn.context().path("body")
                    .has("biologicalSignals");
            if (inChanges && !inContext) {
                return "changes";
            }
            if (inContext && !inChanges) {
                return "context";
            }
            return inChanges ? "both" : "neither";
        }
    }

    private static List<String> changedSlots(PipelineTrace.TurnView turn) {
        List<String> slots = new ArrayList<>();
        for (JsonNode change : turn.changes()) {
            String subject = change.path("subject").textValue();
            change.path("fields").fieldNames().forEachRemaining(
                    name -> slots.add(subject + "." + name)
            );
        }
        return List.copyOf(slots);
    }

    private static void arrived(SemanticPipelineHarness harness) {
        harness.journal(loadGame("10:00:00Z"))
                .journal(jump("10:00:01Z", 23155, "Schieni"))
                .journal(approach("10:00:30Z"))
                .closeBatch();
    }
    private static String approach(String time) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"ApproachBody\",\"StarSystem\":\"Schieni\","
                + "\"SystemAddress\":23155,\"Body\":\"Schieni 4 a\","
                + "\"BodyID\":20}";
    }

    private static String signals(
            String time,
            String eventName,
            String reported
    ) {
        return "{\"timestamp\":\"2026-07-30T" + time + "\",\"event\":\""
                + eventName + "\",\"StarSystem\":\"Schieni\","
                + "\"SystemAddress\":23155,\"BodyID\":20,"
                + "\"BodyName\":\"Schieni 4 a\",\"Signals\":["
                + reported + "]}";
    }

    private static String touchdown(String time) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"Touchdown\",\"StarSystem\":\"Schieni\","
                + "\"SystemAddress\":23155,\"Body\":\"Schieni 4 a\","
                + "\"BodyID\":20,\"PlayerControlled\":true,"
                + "\"Latitude\":1.0,\"Longitude\":2.0}";
    }

    private static String attack(String time) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"UnderAttack\",\"Target\":\"Ship\"}";
    }
}
