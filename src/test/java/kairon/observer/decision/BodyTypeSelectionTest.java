package kairon.observer.decision;

import kairon.observation.ObservationDraft.ObservationCaptureMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A body's type belongs to that body.
 *
 * <p>Only records that select a body report {@code BodyType} — a jump says
 * {@code Star}, a supercruise exit says {@code Planet} — and an approach or a
 * landing says nothing. The field was held until something overwrote it, so
 * arriving at a planet left the arrival star's type standing beside the
 * planet's class, its landability and its signal counts. One body's type
 * describing another.</p>
 *
 * <p>Selecting a different body now drops it, keeping only what was already
 * established for the body being selected. Selecting the same body again keeps
 * what is known: approaching a body twice does not unlearn its type.</p>
 */
final class BodyTypeSelectionTest {

    /** Arriving at a planet does not leave the star's type behind. */
    @Test
    void approachingAPlanetDoesNotInheritTheStarsType(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(ObservationCaptureMode.LIVE, jump())
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, scanOfPlanet())
                    .journal(ObservationCaptureMode.LIVE, approachWithoutType())
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            var state = trace.finalState().orElseThrow();
            assertEquals(
                    "Schieni GG-A c3-64 5",
                    state.bodyName(),
                    "the planet is the selected body"
            );
            assertNull(
                    state.broadBodyType(),
                    "and nothing has said what kind of body it is"
            );
        }
    }

    /** Everything else known about the planet survives. */
    @Test
    void theRestOfTheBodyIsUnaffected(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(ObservationCaptureMode.LIVE, jump())
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, scanOfPlanet())
                    .journal(ObservationCaptureMode.LIVE, approachWithoutType())
                    .closeBatch();

            var state = harness.trace().finalState().orElseThrow();
            assertEquals("High metal content body", state.planetClass());
            assertEquals(Boolean.TRUE, state.landable());
            assertEquals(2356.483967, state.distanceFromArrivalLs());
            assertNull(
                    state.starType(),
                    "and the star's own facts did not follow it either"
            );
        }
    }

    /**
     * A second record for the same body does not unlearn its type.
     *
     * <p>The supercruise exit says {@code Planet}; the landing that follows
     * says nothing, and it is the same body.</p>
     */
    @Test
    void reselectingTheSameBodyKeepsAKnownType(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(ObservationCaptureMode.LIVE, jump())
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, scanOfPlanet())
                    .journal(ObservationCaptureMode.LIVE, supercruiseExit())
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, touchdownWithoutType())
                    .closeBatch();

            assertEquals(
                    "Planet",
                    harness.trace().finalState().orElseThrow().broadBodyType(),
                    "the landing named no type, and none was needed"
            );
        }
    }

    /**
     * Returning to a body recovers the type it was established with.
     *
     * <p>Left, then approached again with a record that reports no type. What
     * a previous record established for that body is what is known about it.
     * </p>
     */
    @Test
    void returningToABodyRecoversItsKnownType(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(ObservationCaptureMode.LIVE, jump())
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, scanOfPlanet())
                    .journal(ObservationCaptureMode.LIVE, supercruiseExit())
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, leaveBody())
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, approachWithoutType())
                    .closeBatch();

            assertEquals(
                    "Planet",
                    harness.trace().finalState().orElseThrow().broadBodyType()
            );
        }
    }

    /** A jump still establishes the arrival star's type. */
    @Test
    void aJumpStillEstablishesTheArrivalStarsType(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(ObservationCaptureMode.LIVE, jump())
                    .closeBatch();

            assertEquals(
                    "Star",
                    harness.trace().finalState().orElseThrow().broadBodyType()
            );
        }
    }

    /**
     * Canonical state and the document agree.
     *
     * <p>The defect was visible model-facing as {@code type: STAR} beside a
     * planet's class, so the contract is checked where it was broken: what the
     * snapshot says about the selected body is what {@code context.body} says.
     * </p>
     */
    @Test
    void theDocumentAgreesWithCanonicalState(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(ObservationCaptureMode.LIVE, jump())
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, scanOfPlanet())
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, approachWithoutType())
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView turn = trace.turns().getLast();
            assertEquals(List.of("BODY_APPROACHED"), turn.eventKinds());
            assertFalse(
                    turn.context().path("body").has("type"),
                    "no type is known, so none is claimed: "
                            + turn.userMessage()
            );
            assertEquals(
                    "High metal content body",
                    turn.context().path("body").path("planetClass").textValue(),
                    "what is known is still sent"
            );
            assertNull(
                    trace.finalState().orElseThrow().broadBodyType(),
                    "and the document says what canonical state says"
            );
        }
    }

    // ------------------------------------------------------------- fixtures

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
                 "StarSystem":"Schieni GG-A c3-64","SystemAddress":17658387800858,
                 "Body":"Schieni GG-A c3-64","BodyID":0,"BodyType":"Star",
                 "JumpDist":2.839,"FuelUsed":0.001857,"FuelLevel":123.360168}
                """;
    }

    /** A real detailed scan: it reports a class, and no {@code BodyType}. */
    private static String scanOfPlanet() {
        return """
                {"timestamp":"2026-07-30T10:01:00Z","event":"Scan",
                 "ScanType":"Detailed","StarSystem":"Schieni GG-A c3-64",
                 "SystemAddress":17658387800858,"BodyID":7,
                 "BodyName":"Schieni GG-A c3-64 5",
                 "PlanetClass":"High metal content body","Landable":true,
                 "WasDiscovered":false,"WasMapped":false,
                 "DistanceFromArrivalLS":2356.483967}
                """;
    }

    private static String approachWithoutType() {
        return """
                {"timestamp":"2026-07-30T10:02:00Z","event":"ApproachBody",
                 "StarSystem":"Schieni GG-A c3-64","SystemAddress":17658387800858,
                 "Body":"Schieni GG-A c3-64 5","BodyID":7}
                """;
    }

    private static String supercruiseExit() {
        return """
                {"timestamp":"2026-07-30T10:02:30Z","event":"SupercruiseExit",
                 "StarSystem":"Schieni GG-A c3-64","SystemAddress":17658387800858,
                 "Body":"Schieni GG-A c3-64 5","BodyID":7,"BodyType":"Planet"}
                """;
    }

    private static String touchdownWithoutType() {
        return """
                {"timestamp":"2026-07-30T10:03:00Z","event":"Touchdown",
                 "StarSystem":"Schieni GG-A c3-64","SystemAddress":17658387800858,
                 "Body":"Schieni GG-A c3-64 5","BodyID":7,
                 "PlayerControlled":true,"Latitude":1.0,"Longitude":2.0}
                """;
    }

    private static String leaveBody() {
        return """
                {"timestamp":"2026-07-30T10:04:00Z","event":"LeaveBody",
                 "StarSystem":"Schieni GG-A c3-64","SystemAddress":17658387800858,
                 "Body":"Schieni GG-A c3-64 5","BodyID":7}
                """;
    }
}
