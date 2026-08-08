package kairon.observer.decision;

import kairon.observation.ObservationDraft.ObservationCaptureMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static kairon.observer.decision.Journal.loadGame;
import static kairon.observer.decision.SemanticPipelineAssertions
        .assertChangesAndContextPartition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A jump arrives in a system; it does not find a body.
 *
 * <p>An {@code FSDJump} carries {@code Body} — the arrival star, whose name is
 * the system's own. Canonical state is right to select it, and two model-facing
 * readings of it were wrong.</p>
 *
 * <p>The occurrence the jump mints carries that star, because the graph records
 * where the ship was; {@code occurrenceOnBody} then presented it as a count of
 * this event at this body, on an event that names no body and can only ever be
 * the first in its own episode.</p>
 *
 * <p>And the selected body becoming the arrival star arrived as a change —
 * {@code body.name: "Schieni GG-A c3-64"} beside {@code system:
 * "Schieni GG-A c3-64"}, reading as a body created or renamed after the system,
 * and on the second jump as {@code UPDATED} from the previous system's name.
 * That change was invisible until the field-aware statement fix: matching by
 * value alone, the event's own {@code system} string suppressed it. The right
 * answer by the wrong route.</p>
 */
final class SystemJumpProjectionTest {

    /** A jump reports the system it arrived in, and nothing about a body. */
    @Test
    void aJumpCarriesNoBodyCountAndNoBodyName(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(ObservationCaptureMode.LIVE, jump(
                            "10:00:01Z",
                            17658387800858L,
                            "Schieni GG-A c3-64"
                    ))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertEquals(1, trace.providerCalls(), trace.describe());
            PipelineTrace.TurnView turn = trace.turns().getLast();
            assertEquals(List.of("SYSTEM_JUMP"), turn.eventKinds());

            var event = turn.events().get(0);
            assertEquals(
                    "Schieni GG-A c3-64",
                    event.path("system").textValue(),
                    "the jump says which system: " + turn.userMessage()
            );
            assertFalse(
                    event.has("occurrenceOnBody"),
                    "an event that names no body has no count at one: "
                            + turn.userMessage()
            );
            assertFalse(
                    event.has("body"),
                    "and it never named one"
            );
            assertTrue(
                    event.has("fuelUsed") && event.has("distanceLy"),
                    "what the jump does say is untouched: "
                            + turn.userMessage()
            );

            assertFalse(
                    changedSlots(turn).contains("body.name"),
                    "arriving at the arrival star is what a jump is, not a "
                            + "body being named after the system: "
                            + turn.userMessage()
            );
            assertFalse(
                    turn.context().path("body").has("name"),
                    "nor is it context: the system name is already in the event"
            );
            assertEquals(
                    "SUPERCRUISE",
                    turn.context().path("navigation").path("flightMode")
                            .textValue(),
                    "the flight mode after a completed jump is unchanged"
            );
            assertChangesAndContextPartition(trace);
        }
    }

    /**
     * Canonical state still knows where the ship is.
     *
     * <p>Only the presentation changed. The arrival star is still selected, so
     * everything that reads the current body — the graph's context snapshot,
     * the body registry, a later scanner reading — is unaffected.</p>
     */
    @Test
    void theArrivalStarIsStillCanonicallySelected(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(ObservationCaptureMode.LIVE, jump(
                            "10:00:01Z",
                            17658387800858L,
                            "Schieni GG-A c3-64"
                    ))
                    .closeBatch();

            var state = harness.trace().finalState().orElseThrow();
            assertEquals("Schieni GG-A c3-64", state.systemName());
            assertEquals(
                    "Schieni GG-A c3-64",
                    state.bodyName(),
                    "the ship really is at the arrival star"
            );
            assertNotNull(state.bodyId());
        }
    }

    /**
     * A second jump does not rename the body either.
     *
     * <p>The worse shape of the same defect: with a body already selected the
     * change arrived as {@code UPDATED}, carrying the previous system's name as
     * {@code before}.</p>
     */
    @Test
    void asecondJumpDoesNotRenameTheBody(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(ObservationCaptureMode.LIVE, jump(
                            "10:00:01Z",
                            17658387800858L,
                            "Schieni GG-A c3-64"
                    ))
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, jump(
                            "10:05:00Z",
                            19857411056410L,
                            "Schieni GG-A c3-72"
                    ))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            assertEquals(2, trace.providerCalls(), trace.describe());
            PipelineTrace.TurnView second = trace.turns().getLast();
            assertEquals(List.of("SYSTEM_JUMP"), second.eventKinds());
            assertEquals(
                    List.of(),
                    changedSlots(second),
                    "nothing was renamed: " + second.userMessage()
            );
            assertFalse(second.events().get(0).has("occurrenceOnBody"));
        }
    }

    /**
     * A completed system survey is scoped to the system too.
     *
     * <p>Found by the same audit and fixed by the same declaration: whichever
     * body was selected when the survey finished — the arrival star, still — is
     * not what the survey is about.</p>
     */
    @Test
    void aCompletedSurveyCarriesNoBodyCount(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(ObservationCaptureMode.LIVE, jump(
                            "10:00:01Z",
                            17658387800858L,
                            "Schieni GG-A c3-64"
                    ))
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, allBodiesFound())
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView turn = trace.turns().getLast();
            assertEquals(List.of("SYSTEM_SURVEY_COMPLETED"), turn.eventKinds());
            assertFalse(
                    turn.events().get(0).has("occurrenceOnBody"),
                    "a survey of the system is not a count at a body: "
                            + turn.userMessage()
            );
            assertEquals(
                    9,
                    turn.events().get(0).path("bodyCount").intValue(),
                    "what it does count is untouched"
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private static List<String> changedSlots(PipelineTrace.TurnView turn) {
        List<String> slots = new ArrayList<>();
        turn.changes().forEach(change -> {
            String subject = change.path("subject").textValue();
            change.path("fields").fieldNames().forEachRemaining(
                    name -> slots.add(subject + "." + name)
            );
        });
        return List.copyOf(slots);
    }
    /** A real jump record, with the arrival star the journal actually sends. */
    private static String jump(String time, long address, String system) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"FSDJump\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"Body\":\"" + system + "\",\"BodyID\":0,"
                + "\"BodyType\":\"Star\",\"JumpDist\":2.839,"
                + "\"FuelUsed\":0.001857,\"FuelLevel\":123.360168}";
    }

    private static String allBodiesFound() {
        return """
                {"timestamp":"2026-07-30T10:02:00Z","event":"FSSAllBodiesFound",
                 "SystemName":"Schieni GG-A c3-64","SystemAddress":17658387800858,
                 "Count":9}
                """;
    }
}
