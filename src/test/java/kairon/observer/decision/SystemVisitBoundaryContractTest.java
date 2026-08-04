package kairon.observer.decision;

import kairon.behavior.model.EpisodeEntrySource;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.normalize.NormalizedEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The graph and the observer begin and end a visit on the same observations.
 *
 * <p>{@code ScannerVisitScopeTest} states the arrival and restore boundaries.
 * These are the four that were never stated anywhere: a {@code Location} that
 * arrives before the Commander and ship are known, a ship switch, a shutdown,
 * and the replay running out of records. Each one is checked on both sides at
 * once — what the graph recorded, and what the provider was actually asked —
 * because a boundary only one layer observes is exactly the shape of the defect
 * the shared policy exists to prevent.</p>
 */
final class SystemVisitBoundaryContractTest {

    /**
     * A restore before an identity opens one visit, once the identity exists.
     *
     * <p>The graph holds it as a pending location and opens the restored
     * episode on the next ordinary observation. The observer defers the same
     * record for the same reason, so neither ends up with a visit belonging to
     * no vessel.</p>
     */
    @Test
    void aLocationBeforeAnIdentityOpensExactlyOneRestoredVisit(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            pipeline.journal(restore("10:00:00Z", 23155, "Schieni"));
            pipeline.journal(loadGame("10:00:01Z"));
            pipeline.journal(supercruiseEntry("10:00:02Z", 23155, "Schieni"));
            pipeline.journal(fss("10:01:00Z", 23155, "Schieni"));
            pipeline.settle();

            List<SystemEpisode> episodes = pipeline.episodes();
            assertEquals(
                    1,
                    episodes.size(),
                    "the deferred restore opened one visit, not one per record"
            );
            SystemEpisode visit = episodes.getFirst();
            assertEquals(
                    EpisodeEntrySource.LOCATION_RESTORE,
                    visit.entrySource(),
                    "a restored session is not an arrival"
            );
            assertEquals(
                    List.of(
                            NormalizedEventType.SUPERCRUISE_ENTRY,
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    ),
                    types(visit),
                    "and its first structural event takes no incoming edge"
            );
            assertTrue(
                    pipeline.modelFacingKinds()
                            .contains("BODY_SIGNALS_FOUND"),
                    "the finding of that one visit reached the model: "
                            + pipeline.modelFacingKinds()
            );
        }
    }

    /**
     * A ship switch is a new visit to both, in the system already in progress.
     *
     * <p>The graph completes its episode and mints a ship-switch root; the
     * observer clears the findings it has already reported. A finding restated
     * on the new ship is therefore the new ship's first.</p>
     */
    @Test
    void aShipSwitchBeginsAVisitInBothLayers(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            pipeline.journal(loadGame("10:00:00Z"));
            pipeline.journal(jump("10:00:01Z", 23155, "Schieni"));
            pipeline.journal(fss("10:01:00Z", 23155, "Schieni"));
            pipeline.settle();
            int afterFirstShip = pipeline.modelInputs().size();

            pipeline.journal(loadGameOnShip("10:02:00Z", 14));
            pipeline.journal(fss("10:03:00Z", 23155, "Schieni"));
            pipeline.settle();

            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    ),
                    types(pipeline.activeEpisode()),
                    "the new ship's visit is rooted at the switch and "
                            + "recorded the finding as its own"
            );
            assertEquals(
                    afterFirstShip + 1,
                    pipeline.modelInputs().size(),
                    "and the model was told about it"
            );
            assertEquals(
                    "BODY_SIGNALS_FOUND",
                    pipeline.modelFacingKinds().getLast()
            );
        }
    }

    /**
     * A shutdown ends the visit, and nothing survives it.
     *
     * <p>Stated on the finding rather than on the episode alone: the graph
     * completing an episode while the observer's memory outlives it is the
     * failure, and it is only visible when the same reading arrives again.</p>
     */
    @Test
    void aShutdownEndsTheVisitInBothLayers(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            pipeline.journal(loadGame("10:00:00Z"));
            pipeline.journal(jump("10:00:01Z", 23155, "Schieni"));
            pipeline.journal(fss("10:01:00Z", 23155, "Schieni"));
            pipeline.settle();
            int beforeShutdown = pipeline.modelInputs().size();

            pipeline.journal(shutdown("10:02:00Z"));
            pipeline.journal(restore("10:03:00Z", 23155, "Schieni"));
            pipeline.journal(fss("10:04:00Z", 23155, "Schieni"));
            pipeline.settle();

            List<SystemEpisode> episodes = pipeline.episodes();
            assertEquals(2, episodes.size(), "two visits to one system");
            assertEquals(
                    List.of(NormalizedEventType.FSS_BODY_SIGNALS_FOUND),
                    types(episodes.getLast()),
                    "the second visit recorded the reading as its own"
            );
            assertEquals(
                    beforeShutdown + 1,
                    pipeline.modelInputs().size(),
                    "and told the model about it"
            );
        }
    }

    /**
     * Running out of records ends the visit for the observer too.
     *
     * <p>The graph completes its episode on replay exhaustion. The observer's
     * novelty memory used to survive it, so a second run over the same journal
     * would have reported nothing. Stated on the memory rather than on a second
     * run: the guard is cleared by the same signal the graph completes on.</p>
     */
    @Test
    void replayExhaustionEndsTheVisitInBothLayers(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            pipeline.journal(loadGame("10:00:00Z"));
            pipeline.journal(jump("10:00:01Z", 23155, "Schieni"));
            pipeline.journal(fss("10:01:00Z", 23155, "Schieni"));
            pipeline.settle();
            int beforeExhaustion = pipeline.modelInputs().size();

            pipeline.replayExhausted("2026-07-30T10:02:00Z");
            pipeline.settle();

            assertEquals(
                    List.of(),
                    pipeline.episodes().stream()
                            .filter(SystemEpisode::active)
                            .toList(),
                    "the graph closed the visit it was in"
            );

            pipeline.journal(jump("10:03:00Z", 23155, "Schieni"));
            pipeline.journal(fss("10:04:00Z", 23155, "Schieni"));
            pipeline.settle();

            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    ),
                    types(pipeline.activeEpisode()),
                    "and the visit after it recorded the reading as its own"
            );
            assertTrue(
                    pipeline.modelInputs().size() > beforeExhaustion,
                    "so the observer had nothing left to suppress it against"
            );
            assertEquals(
                    "BODY_SIGNALS_FOUND",
                    pipeline.modelFacingKinds().getLast()
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private static List<NormalizedEventType> types(SystemEpisode episode) {
        return episode.timeline().stream()
                .map(EventOccurrence::eventType)
                .toList();
    }

    private static DecisionProductionPipeline perTrigger(Path directory) {
        return new DecisionProductionPipeline(
                directory,
                new DecisionTurnPolicy(1, 16_000)
        );
    }

    private static String loadGame(String time) {
        return loadGameOnShip(time, 9);
    }

    private static String loadGameOnShip(String time, int shipId) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"LoadGame\",\"FID\":\"F12345678\","
                + "\"ShipID\":" + shipId + ",\"Ship\":\"explorer_nx\","
                + "\"ShipName\":\"Wanderer\"}";
    }

    private static String jump(String time, long address, String system) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"FSDJump\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"JumpDist\":8.5,\"FuelUsed\":0.4,\"FuelLevel\":30.2}";
    }

    private static String restore(String time, long address, String system) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"Location\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address + ",\"Docked\":false}";
    }

    private static String supercruiseEntry(
            String time,
            long address,
            String system
    ) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"SupercruiseEntry\",\"StarSystem\":\""
                + system + "\",\"SystemAddress\":" + address + "}";
    }

    private static String shutdown(String time) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"Shutdown\"}";
    }

    private static String fss(String time, long address, String system) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"FSSBodySignals\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"BodyID\":20,\"BodyName\":\"" + system + " 4 a\","
                + "\"Signals\":[{\"Type\":\"$SAA_SignalType_Biological;\","
                + "\"Count\":1}]}";
    }
}
