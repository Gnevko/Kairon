package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observer.decision.DecisionEventProjector.ProjectedEvent;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static kairon.observer.decision.SemanticPipelineAssertions
        .assertChangesAndContextPartition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An event states a fact, not a number.
 *
 * <p>{@code ProjectedEvent.states} used to ask only whether some field of the
 * event carried an equal {@link SemanticValue}. A landing reporting
 * {@code occurrenceOnBody: 1} therefore counted as having stated every canonical
 * field whose value happened to be one — so one biological signal was suppressed
 * from {@code changes} and two were not, and which section a fact appeared in
 * depended on an unrelated integer.</p>
 *
 * <p>Both halves now have to match: the canonical field and its value. These
 * tests hold the fix from both ends — the predicate itself, against events built
 * by the production projector, and the document, through the whole pipeline.</p>
 */
final class FieldAwareStatementTest {

    private final DecisionEventProjector projector =
            new DecisionEventProjector();

    // ------------------------------------------------ the predicate itself

    /**
     * A5: a real duplicate is still a duplicate.
     *
     * <p>Read off a scanner rather than a landing. A landing no longer names
     * the body it happened on — the situation answers for where the ship is —
     * so it has no body name for the predicate to match. A reading does: the
     * body it reports is its own, not the one under the ship.</p>
     */
    @Test
    void anEventStatesTheFieldItNamesAtTheValueItNames(
            @TempDir Path directory
    ) {
        ProjectedEvent event = lastProjected(directory, bodySignals());

        assertTrue(
                event.states(
                        SemanticField.BODY_NAME,
                        SemanticValue.ofText("Schieni 4 a")
                ),
                "the reading names the body it is about"
        );
    }

    /** A2: an equal value under another field states nothing. */
    @Test
    void anEqualValueUnderAnotherFieldIsNotAStatement(
            @TempDir Path directory
    ) {
        ProjectedEvent event = touchdown(directory);

        assertTrue(
                event.states(
                        SemanticField.FLIGHT_MODE,
                        SemanticValue.ofSymbol("LANDED")
                ),
                "the fixture must really state something"
        );
        assertEquals(
                1,
                event.event().fields().stream()
                        .filter(field ->
                                "occurrenceOnBody".equals(field.name()))
                        .count(),
                "the fixture must really carry occurrenceOnBody"
        );
        assertFalse(
                event.states(
                        SemanticField.BIOLOGICAL_SIGNAL_COUNT,
                        SemanticValue.ofIntegral(1)
                ),
                "occurrenceOnBody: 1 says nothing about biological signals"
        );
        assertFalse(
                event.states(
                        SemanticField.GEOLOGICAL_SIGNAL_COUNT,
                        SemanticValue.ofIntegral(1)
                )
        );
    }

    /** A6: the same field at another value is not stated either. */
    @Test
    void aDifferentValueForTheSameFieldIsNotAStatement(
            @TempDir Path directory
    ) {
        ProjectedEvent event = lastProjected(directory, bodySignals());

        assertFalse(
                event.states(
                        SemanticField.BODY_NAME,
                        SemanticValue.ofText("Somewhere else")
                ),
                "naming one body is not naming another"
        );
    }

    /**
     * A field the event never mentions is never stated.
     *
     * <p>The flight mode is the exception that proves it: a landing carries no
     * flight-mode field either, and states it anyway — because its sentence
     * says the ship is down, and the rule declares that
     * ({@code DecisionEventRule.statedValues}). It holds at that one value: the
     * same landing says nothing about being in supercruise.</p>
     */
    @Test
    void aFieldTheEventDoesNotCarryIsNotStated(@TempDir Path directory) {
        ProjectedEvent event = touchdown(directory);

        assertFalse(event.states(
                SemanticField.PLANET_CLASS,
                SemanticValue.ofSymbol("Icy body")
        ));
        assertTrue(event.states(
                SemanticField.FLIGHT_MODE,
                SemanticValue.ofSymbol("LANDED")
        ), "the landing says the ship is down in its own words");
        assertFalse(event.states(
                SemanticField.FLIGHT_MODE,
                SemanticValue.ofSymbol("SUPERCRUISE")
        ), "and says nothing about a mode it is not in");
    }

    /**
     * A reported count states its own category, and no other.
     *
     * <p>The categories arrive as one count each, named as the context names
     * them, so a canonical field is matched by the ordinary rule: same name,
     * same value. It used to be one nested set whose categories had to be
     * declared to count canonical fields before anything could see them, and
     * the declaration went with the shape.</p>
     *
     * <p>What the declaration protected still holds and is asserted below: a
     * number is never matched against a field it might belong to. The human
     * count of three does not state a biological three, and a category the
     * reading omitted states nothing at all.</p>
     */
    @Test
    void aReportedCountStatesOnlyItsOwnCategory(
            @TempDir Path directory
    ) {
        ProjectedEvent event = lastProjected(directory, """
                {"timestamp":"2026-07-30T10:01:00Z","event":"FSSBodySignals",
                 "StarSystem":"Schieni","SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni 4 a",
                 "Signals":[{"Type":"$SAA_SignalType_Biological;","Count":1},
                            {"Type":"$SAA_SignalType_Human;","Count":3}]}
                """);

        assertTrue(
                event.event().fields().stream()
                        .anyMatch(field ->
                                "biologicalSignals".equals(field.name())),
                "the fixture must really carry a reported count"
        );
        assertTrue(
                event.states(
                        SemanticField.BIOLOGICAL_SIGNAL_COUNT,
                        SemanticValue.ofIntegral(1)
                ),
                "biology is counted, and biology is a canonical field"
        );
        assertFalse(
                event.states(
                        SemanticField.BIOLOGICAL_SIGNAL_COUNT,
                        SemanticValue.ofIntegral(3)
                ),
                "the human count is not the biological one"
        );
        assertFalse(
                event.states(
                        SemanticField.GEOLOGICAL_SIGNAL_COUNT,
                        SemanticValue.ofIntegral(1)
                ),
                "a category the reading omits states nothing at all"
        );
        assertFalse(
                event.states(
                        SemanticField.GEOLOGICAL_SIGNAL_COUNT,
                        SemanticValue.ofIntegral(3)
                )
        );
    }

    // ------------------------------------------------------- the document

    /**
     * A3: two unrelated booleans no longer collide.
     *
     * <p>A landing says {@code commanderControlled: true}, and whether the body
     * can be landed on is also {@code true}. Under the old rule the second was
     * suppressed because {@code true} equalled {@code true} — a value alone,
     * with no canonical field beside it, proved nothing and suppressed
     * anything.</p>
     *
     * <p>Asserted on the predicate rather than on the document, because the
     * document no longer has anywhere to show it: {@code landable} was one of
     * the standing facts that stopped riding on {@code context.body}. The claim
     * is unchanged and this is where it lives — an event states a field it
     * named, at the value it named, and an equal boolean elsewhere is not
     * that.</p>
     */
    @Test
    void anEqualBooleanInAnotherFieldNoLongerSuppressesAFact(
            @TempDir Path directory
    ) {
        ProjectedEvent event = touchdown(directory);

        assertTrue(
                event.event().fields().stream().anyMatch(field ->
                        "commanderControlled".equals(field.name())
                                && SemanticValue.ofBoolean(true)
                                .equals(field.value())),
                "the landing states who was flying, and states it as true"
        );
        assertFalse(
                event.states(
                        SemanticField.LANDABLE,
                        SemanticValue.ofBoolean(true)
                ),
                "who was flying says nothing about whether it can be landed on"
        );
    }

    /** A5 end to end: a real duplicate is still dropped. */
    @Test
    void anEventThatReallyStatesAFactStillSuppressesIt(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(ObservationCaptureMode.BOOTSTRAP, jump())
                    .journal(ObservationCaptureMode.BOOTSTRAP, approach())
                    .settleProjection();
            harness.journal(ObservationCaptureMode.LIVE, touchdownJson())
                    .closeBatch();
            PipelineTrace trace = harness.trace();
            PipelineTrace.TurnView turn = trace.turns().getLast();

            assertEquals(
                    "Schieni GG-A c3-84 4 a",
                    turn.context().path("body").path("name").textValue(),
                    "the situation names the body the landing happened on: "
                            + turn.userMessage()
            );
            assertFalse(
                    turn.events().get(0).has("body"),
                    "and the landing does not name it a second time"
            );
            assertFalse(
                    changedSlots(turn).contains("body.name"),
                    "nor is it reported as a change: " + turn.userMessage()
            );
            assertChangesAndContextPartition(trace);
        }
    }

    // ------------------------------------------------------------ fixtures

    /**
     * A landing in a real visit, so it really carries its count.
     *
     * <p>{@code occurrenceOnBody} is the field the old value-only rule collided
     * with, so a fixture without it would not exercise the fix at all. It only
     * exists where a real episode established the body, which is why this goes
     * through the production pipeline rather than a scripted situation.</p>
     */
    private ProjectedEvent touchdown(Path directory) {
        return lastProjected(directory, touchdownJson());
    }

    /** A reading that names the body it is about. */
    private static String bodySignals() {
        return """
                {"timestamp":"2026-07-30T10:01:00Z","event":"FSSBodySignals",
                 "StarSystem":"Schieni","SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni 4 a",
                 "Signals":[{"Type":"$SAA_SignalType_Biological;","Count":1}]}
                """;
    }

    /**
     * The projected event for the last observation of a real short run.
     *
     * <p>Production parser, projector, graph and semantic envelope; only the
     * event projection under test is invoked directly.</p>
     */
    private ProjectedEvent lastProjected(Path directory, String rawJson) {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            pipeline.journal(loadGame());
            pipeline.journal(jump());
            pipeline.journal(approach());
            pipeline.journal(rawJson);
            pipeline.settleProjection();
            return projector.project(
                    1,
                    pipeline.capturedProjections().getLast()
            );
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
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

    private static String loadGame() {
        return """
                {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """;
    }

    private static String jump() {
        return """
                {"timestamp":"2026-07-30T10:00:01Z","event":"FSDJump",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "JumpDist":8.5,"FuelUsed":0.4,"FuelLevel":30.2}
                """;
    }

    private static String approach() {
        return """
                {"timestamp":"2026-07-30T10:00:30Z","event":"ApproachBody",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20}
                """;
    }

    private static String scan() {
        return """
                {"timestamp":"2026-07-30T10:00:40Z","event":"Scan",
                 "ScanType":"Detailed","StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni GG-A c3-84 4 a","PlanetClass":"Icy body",
                 "Landable":true,"WasDiscovered":false,"WasMapped":false}
                """;
    }

    /**
     * The same reading the ship takes on its own, flying past.
     *
     * <p>Projected exactly as any other scan — the projector does not read
     * {@code ScanType} — but declined as a trigger, so it establishes the body
     * without opening a turn about it.</p>
     */
    private static String automaticScan() {
        return """
                {"timestamp":"2026-07-30T10:00:40Z","event":"Scan",
                 "ScanType":"AutoScan","StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni GG-A c3-84 4 a","PlanetClass":"Icy body",
                 "Landable":true,"WasDiscovered":false,"WasMapped":false}
                """;
    }

    private static String touchdownJson() {
        return """
                {"timestamp":"2026-07-30T10:01:00Z","event":"Touchdown",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "PlayerControlled":true,"Latitude":1.0,"Longitude":2.0}
                """;
    }
}
