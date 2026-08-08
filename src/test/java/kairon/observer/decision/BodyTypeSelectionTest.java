package kairon.observer.decision;

import kairon.observation.ObservationDraft.ObservationCaptureMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static kairon.observer.decision.Journal.loadGame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A body's type belongs to that body.
 *
 * <p>Only records that select a body report {@code BodyType} — a jump says
 * {@code Star}, a supercruise exit says {@code Planet} — and an approach or a
 * landing says nothing. Held as a field of the current body, the type stayed
 * put until something overwrote it, so arriving at a planet left the arrival
 * star's type standing beside the planet's class, its landability and its
 * signal counts. One body's type describing another.</p>
 *
 * <p>Two bodies are now two entries in the current-system registry
 * (ADR-0025), so neither can be described by the other's facts, and what is
 * known about a body is what records about <em>that</em> body established.</p>
 */
final class BodyTypeSelectionTest {

    private static final long SYSTEM = 17658387800858L;
    private static final long STAR = 0L;
    private static final long PLANET = 7L;

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

            assertEquals(
                    "Schieni GG-A c3-64 5",
                    trace.finalState().orElseThrow().bodyName(),
                    "the planet is the selected body"
            );
            assertEquals(
                    "PLANET",
                    trace.finalBody(SYSTEM, PLANET).broadBodyType(),
                    "and it is what the scan established it to be"
            );
            assertEquals(
                    "STAR",
                    trace.finalBody(SYSTEM, STAR).broadBodyType(),
                    "while the star stayed the star"
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

            var planet = harness.trace().finalBody(SYSTEM, PLANET);
            assertEquals("High metal content body", planet.planetClass());
            assertEquals(Boolean.TRUE, planet.landable());
            assertEquals(2356.483967, planet.distanceFromArrivalLs());
            assertNull(
                    planet.starType(),
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
                    "PLANET",
                    harness.trace().finalBody(SYSTEM, PLANET).broadBodyType(),
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
                    "PLANET",
                    harness.trace().finalBody(SYSTEM, PLANET).broadBodyType()
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
                    "STAR",
                    harness.trace().finalBody(SYSTEM, STAR).broadBodyType()
            );
        }
    }

    /**
     * The type is what the body is, not whether a record happened to say so.
     *
     * <p>The defect was visible model-facing as {@code type: STAR} beside a
     * planet's class, and the first repair was to send no type at all unless a
     * record spelled one out — which left a scanned planet typeless, because a
     * {@code Scan} carries {@code PlanetClass} and never {@code BodyType}. The
     * registry classifies the body from the class the scan did report, so the
     * answer now comes from what was established rather than from which field
     * the game chose to put it in.</p>
     */
    @Test
    void aScannedPlanetIsTypedFromWhatTheScanEstablished(
            @TempDir Path directory
    ) {
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
            assertEquals(
                    "PLANET",
                    turn.context().path("body").path("type").textValue(),
                    "no record named the type; the scan established it: "
                            + turn.userMessage()
            );
            assertEquals(
                    "High metal content body",
                    turn.context().path("body").path("planetClass").textValue(),
                    "beside the class it was read from"
            );
            assertNull(
                    trace.finalBody(SYSTEM, PLANET).starType(),
                    "and nothing about a star came with it"
            );
        }
    }

    // ------------------------------------------------------------- fixtures
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
