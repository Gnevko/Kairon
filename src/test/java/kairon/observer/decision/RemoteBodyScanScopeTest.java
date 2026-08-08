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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One turn describes one body.
 *
 * <p>A detailed scan taken from across the system reads a planet without going
 * anywhere, so canonical state still answers for the arrival star. Both reached
 * the document under {@code body}: the event described the planet while
 * {@code context.body} described the star — its type, how far from arrival it
 * is, whether anyone had been there — and the star's name, the one thing that
 * would have shown they were different bodies, was suppressed because it equals
 * the system name the event already states.</p>
 *
 * <p>The arrival star's own facts also arrived as {@code changes} in one of
 * these turns. The record that established them is the automatic scan taken on
 * arrival, which is declined as a trigger and so opens no turn of its own; its
 * effect is retained and surfaces in the next turn, which is about a different
 * body entirely.</p>
 */
final class RemoteBodyScanScopeTest {

    /** A scan of a distant planet carries no facts about the arrival star. */
    @Test
    void aRemoteScanCarriesNoFactsAboutTheArrivalStar(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrivedAndAutoScanned(harness);
            harness.journal(ObservationCaptureMode.LIVE, detailedScan(
                            "10:02:00Z",
                            7,
                            "Schieni GG-A c3-64 5",
                            2356.483967
                    ))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView turn = trace.turns().getLast();
            assertEquals(List.of("BODY_SCANNED"), turn.eventKinds());
            assertEquals(
                    "Schieni GG-A c3-64 5",
                    turn.events().get(0).path("body").textValue(),
                    "the turn is about the planet: " + turn.userMessage()
            );

            assertFalse(
                    turn.context().has("body"),
                    "the star the ship is at is not the body this turn is "
                            + "about: " + turn.userMessage()
            );
            assertEquals(
                    List.of(),
                    bodyChangedSlots(turn),
                    "and neither are its facts a change here: "
                            + turn.userMessage()
            );
            assertTrue(
                    turn.events().get(0).has("planetClass"),
                    "what is known about the scanned body is in the event, "
                            + "where it belongs"
            );
            assertChangesAndContextPartition(trace);
        }
    }

    /**
     * The star's own facts are not lost — they are simply not this turn's.
     *
     * <p>Canonical state still holds them, and the arrival star is still the
     * selected body. Only the presentation stopped attributing them to a turn
     * about somewhere else.</p>
     */
    @Test
    void theArrivalStarIsStillCanonicallyKnown(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrivedAndAutoScanned(harness);
            harness.journal(ObservationCaptureMode.LIVE, detailedScan(
                            "10:02:00Z",
                            7,
                            "Schieni GG-A c3-64 5",
                            2356.483967
                    ))
                    .closeBatch();

            var state = harness.trace().finalState().orElseThrow();
            assertEquals(
                    "Schieni GG-A c3-64",
                    state.bodyName(),
                    "a remote scan does not move the ship"
            );
            var star = harness.trace().finalBody(17658387800858L, 0L);
            assertEquals("K", star.starType());
            assertEquals(0.0, star.distanceFromArrivalLs());
        }
    }

    /**
     * A batch reporting several bodies reports none of them from state.
     *
     * <p>No single canonical body can be all of them, and a survey batch
     * routinely carries three or four scans at once.</p>
     */
    @Test
    void aBatchOfScansCarriesNoBodyContext(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrivedAndAutoScanned(harness);
            harness.journal(ObservationCaptureMode.LIVE, detailedScan(
                            "10:02:00Z", 7, "Schieni GG-A c3-64 5", 2356.4
                    ))
                    .journal(ObservationCaptureMode.LIVE, detailedScan(
                            "10:02:05Z", 8, "Schieni GG-A c3-64 6", 2984.8
                    ))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView turn = trace.turns().getLast();
            assertEquals(
                    List.of("BODY_SCANNED", "BODY_SCANNED"),
                    turn.eventKinds()
            );
            assertFalse(
                    turn.context().has("body"),
                    "two bodies in one turn are not one body: "
                            + turn.userMessage()
            );
            assertEquals(List.of(), bodyChangedSlots(turn));
        }
    }

    /**
     * Being at the body still sends everything known about it.
     *
     * <p>The scope rule must not cost the case it exists to protect: an
     * approach names the body it arrives at, canonical state answers for that
     * same body, and the context is exactly what makes an arrival worth
     * remarking on.</p>
     */
    @Test
    void arrivingAtTheScannedBodyKeepsItsContext(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrivedAndAutoScanned(harness);
            harness.journal(ObservationCaptureMode.LIVE, detailedScan(
                            "10:02:00Z",
                            7,
                            "Schieni GG-A c3-64 5",
                            2356.483967
                    ))
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, approach(
                            "10:03:00Z",
                            7,
                            "Schieni GG-A c3-64 5"
                    ))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView turn = trace.turns().getLast();
            assertEquals(List.of("BODY_APPROACHED"), turn.eventKinds());
            assertEquals(
                    "Icy body",
                    turn.context().path("body").path("planetClass").textValue(),
                    "what is known about the body being arrived at is sent: "
                            + turn.userMessage()
            );
        }
    }

    /**
     * A turn that names no body still describes where the ship is.
     *
     * <p>A sample names an organism, not a place, and its mechanism asks for
     * the body in detail — which body it is standing on is the whole point.
     * Nothing names another body, so there is nothing to confuse it with and
     * the body the ship is at answers for itself, name included.</p>
     */
    @Test
    void aTurnNamingNoBodyKeepsItsContext(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrivedAndAutoScanned(harness);
            harness.journal(ObservationCaptureMode.LIVE, detailedScan(
                            "10:02:00Z",
                            7,
                            "Schieni GG-A c3-64 5",
                            2356.483967
                    ))
                    .journal(ObservationCaptureMode.LIVE, approach(
                            "10:03:00Z",
                            7,
                            "Schieni GG-A c3-64 5"
                    ))
                    .closeBatch();
            harness.journal(ObservationCaptureMode.LIVE, sample())
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView turn = trace.turns().getLast();
            assertEquals(List.of("BIOLOGICAL_SAMPLE"), turn.eventKinds());
            assertFalse(
                    turn.events().get(0).has("body"),
                    "a sample names no body: " + turn.userMessage()
            );
            assertEquals(
                    "Schieni GG-A c3-64 5",
                    turn.context().path("body").path("name").textValue(),
                    "so the body it is standing on is sent, and named: "
                            + turn.userMessage()
            );
            assertEquals(
                    "Icy body",
                    turn.context().path("body").path("planetClass").textValue()
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    /**
     * Arrival, then the automatic scan of the arrival star.
     *
     * <p>The auto scan is what established the star's type and distance in the
     * observed run. It is declined as a trigger, so it opens no turn and its
     * effect waits for whatever turn comes next — which is the point.</p>
     *
     * <p>The star is one somebody had already found. The one automatic reading
     * that is <em>not</em> declined is an undiscovered arrival star, which is a
     * milestone with a turn of its own; using that here would put the star's
     * facts in the batch legitimately and stop this fixture expressing a hidden
     * effect at all. What is being pinned is the hidden case, so the reading has
     * to stay hidden.</p>
     */
    private static void arrivedAndAutoScanned(SemanticPipelineHarness harness) {
        harness.journal(loadGame())
                .journal(ObservationCaptureMode.LIVE, jump())
                .closeBatch();
        harness.journal(ObservationCaptureMode.LIVE, autoScanOfArrivalStar())
                .settleProjection();
    }

    private static List<String> bodyChangedSlots(PipelineTrace.TurnView turn) {
        List<String> slots = new ArrayList<>();
        turn.changes().forEach(change -> {
            if (!"body".equals(change.path("subject").textValue())) {
                return;
            }
            change.path("fields").fieldNames().forEachRemaining(
                    name -> slots.add("body." + name)
            );
        });
        return List.copyOf(slots);
    }
    private static String jump() {
        return """
                {"timestamp":"2026-07-30T10:00:01Z","event":"FSDJump",
                 "StarSystem":"Schieni GG-A c3-64","SystemAddress":17658387800858,
                 "Body":"Schieni GG-A c3-64","BodyID":0,"BodyType":"Star",
                 "JumpDist":2.839,"FuelUsed":0.001857,"FuelLevel":123.360168}
                """;
    }

    private static String autoScanOfArrivalStar() {
        return """
                {"timestamp":"2026-07-30T10:01:00Z","event":"Scan",
                 "ScanType":"AutoScan","StarSystem":"Schieni GG-A c3-64",
                 "SystemAddress":17658387800858,"BodyID":0,
                 "BodyName":"Schieni GG-A c3-64","StarType":"K",
                 "DistanceFromArrivalLS":0.0,"WasDiscovered":true,
                 "WasMapped":false}
                """;
    }

    private static String detailedScan(
            String time,
            long bodyId,
            String bodyName,
            double distance
    ) {
        return "{\"timestamp\":\"2026-07-30T" + time + "\",\"event\":\"Scan\","
                + "\"ScanType\":\"Detailed\","
                + "\"StarSystem\":\"Schieni GG-A c3-64\","
                + "\"SystemAddress\":17658387800858,\"BodyID\":" + bodyId
                + ",\"BodyName\":\"" + bodyName + "\","
                + "\"PlanetClass\":\"Icy body\",\"Landable\":false,"
                + "\"WasDiscovered\":false,\"WasMapped\":false,"
                + "\"DistanceFromArrivalLS\":" + distance + "}";
    }

    private static String approach(String time, long bodyId, String bodyName) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"ApproachBody\","
                + "\"StarSystem\":\"Schieni GG-A c3-64\","
                + "\"SystemAddress\":17658387800858,\"Body\":\"" + bodyName
                + "\",\"BodyID\":" + bodyId + "}";
    }

    private static String sample() {
        return """
                {"timestamp":"2026-07-30T10:04:00Z","event":"ScanOrganic",
                 "ScanType":"Log","Genus":"$Codex_Ent_Bacterial_Genus_Name;",
                 "Genus_Localised":"Bacteria",
                 "Species":"$Codex_Ent_Bacterial_10_Name;",
                 "Species_Localised":"Bacterium Bullaris",
                 "Variant":"$Codex_Ent_Bacterial_10_Yttrium_Name;",
                 "Variant_Localised":"Bacterium Bullaris - Red",
                 "WasLogged":false,"SystemAddress":17658387800858,"Body":7}
                """;
    }
}
