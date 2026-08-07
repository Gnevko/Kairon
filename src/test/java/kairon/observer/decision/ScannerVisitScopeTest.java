package kairon.observer.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A finding is new once per visit, and the two owners agree on what a visit is.
 *
 * <p>The behaviour graph deduplicates scanner results inside one
 * {@code SystemEpisode}; the observer used to remember them per system address,
 * for as long as the process lived. Coming back to a system therefore produced
 * an occurrence the graph considered new and a turn the observer considered a
 * repeat. The observer now begins a visit on the same observations the graph
 * opens an episode on — and derives them itself, so nothing here depends on the
 * graph being enabled or on anything it has persisted.</p>
 *
 * <p>Everything here runs the production parser, projector and behaviour graph
 * against isolated temporary storage. The provider is a stub that cannot
 * influence what is built.</p>
 */
final class ScannerVisitScopeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------ C. visits

    /** C1: the same finding twice in one visit is one finding. */
    @Test
    void aRepeatWithinOneVisitIsSuppressed(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrivedIn(pipeline, "Schieni", 23155, "10:00:01Z");
            int before = pipeline.modelInputs().size();

            pipeline.journal(fss("10:01:00Z", 23155, "Schieni"));
            pipeline.settle();
            pipeline.journal(fss("10:01:30Z", 23155, "Schieni"));
            pipeline.settle();

            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    )
            );
            assertEquals(before + 1, pipeline.modelInputs().size());
        }
    }

    /** C2: a session resumed in the same system is a second look at it. */
    @Test
    void aRestoredVisitIntoTheSameSystemReportsAgain(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            pipeline.journal(loadGame("10:00:00Z"));
            pipeline.journal(restore("10:00:01Z", 23155, "Schieni"));
            pipeline.journal(fss("10:01:00Z", 23155, "Schieni"));
            pipeline.settle();
            int afterFirstVisit = pipeline.modelInputs().size();

            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:02:00Z","event":"Shutdown"}
                    """);
            pipeline.journal(restore("10:03:00Z", 23155, "Schieni"));
            pipeline.journal(fss("10:04:00Z", 23155, "Schieni"));
            pipeline.settle();

            List<SystemEpisode> episodes = pipeline.episodes();
            assertEquals(2, episodes.size());
            for (SystemEpisode episode : episodes) {
                assertEquals(
                        List.of(NormalizedEventType.FSS_BODY_SIGNALS_FOUND),
                        episode.timeline().stream()
                                .map(occurrence -> occurrence.eventType())
                                .toList(),
                        "each visit recorded the reading once"
                );
            }
            assertEquals(
                    afterFirstVisit + 1,
                    pipeline.modelInputs().size(),
                    "the second look reported its finding too"
            );
        }
    }

    /** C3: leaving and coming back is a second look as well. */
    @Test
    void leavingAndReturningReportsAgain(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrivedIn(pipeline, "Schieni", 23155, "10:00:01Z");
            pipeline.journal(fss("10:01:00Z", 23155, "Schieni"));
            pipeline.settle();
            int afterFirstVisit = pipeline.modelInputs().size();

            jumpTo(pipeline, "Elsewhere", 99001, "10:02:00Z");
            jumpTo(pipeline, "Schieni", 23155, "10:03:00Z");
            pipeline.journal(fss("10:04:00Z", 23155, "Schieni"));
            pipeline.settle();

            assertEquals(
                    2L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    ),
                    "one occurrence per visit"
            );
            assertEquals(
                    afterFirstVisit + 3,
                    pipeline.modelInputs().size(),
                    "two jumps and the second reading"
            );
            assertEquals(
                    "BODY_SIGNALS_FOUND",
                    pipeline.modelFacingKinds().getLast()
            );
        }
    }

    /** C4: restating where the Commander already is resets nothing. */
    @Test
    void aRepeatedLocationInsideOneVisitDoesNotResetTheMemory(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            pipeline.journal(loadGame("10:00:00Z"));
            pipeline.journal(restore("10:00:01Z", 23155, "Schieni"));
            pipeline.journal(fss("10:01:00Z", 23155, "Schieni"));
            pipeline.settle();
            int afterFirstReading = pipeline.modelInputs().size();

            pipeline.journal(restore("10:02:00Z", 23155, "Schieni"));
            pipeline.journal(fss("10:03:00Z", 23155, "Schieni"));
            pipeline.settle();

            assertEquals(1, pipeline.episodes().size());
            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    )
            );
            assertEquals(
                    afterFirstReading,
                    pipeline.modelInputs().size(),
                    "the visit never ended, so the repeat is still a repeat"
            );
        }
    }

    // --------------------------------------------------------- D. bootstrap

    /** D1: a historical survey the model never saw silences nothing. */
    @Test
    void aBootstrapSurveyDoesNotSilenceTheLiveReadingThatRepeatsIt(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrivedIn(pipeline, "Schieni", 23155, "10:00:01Z");
            int beforeSignals = pipeline.modelInputs().size();

            pipeline.journal(
                    ObservationCaptureMode.BOOTSTRAP,
                    saa("10:01:00Z", 23155, "Schieni")
            );
            pipeline.settle();
            assertEquals(
                    beforeSignals,
                    pipeline.modelInputs().size(),
                    "historical capture is model-silent"
            );
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    "and it is not structural either: the occurrence would "
                            + "belong to a finding nobody was told about"
            );

            pipeline.journal(
                    ObservationCaptureMode.LIVE,
                    fss("10:02:00Z", 23155, "Schieni")
            );
            pipeline.settle();

            assertEquals(
                    beforeSignals + 1,
                    pipeline.modelInputs().size(),
                    "the live reading is new to the model"
            );
            assertEquals(
                    "BODY_SIGNALS_FOUND",
                    pipeline.modelFacingKinds().getLast()
            );
            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    ),
                    "the event the model was given has an occurrence of its own"
            );
        }
    }

    /** D2: and it works the other way round. */
    @Test
    void aBootstrapSystemScanDoesNotSilenceTheLiveSurvey(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrivedIn(pipeline, "Schieni", 23155, "10:00:01Z");
            int beforeSignals = pipeline.modelInputs().size();

            pipeline.journal(
                    ObservationCaptureMode.BOOTSTRAP,
                    fss("10:01:00Z", 23155, "Schieni")
            );
            pipeline.journal(
                    ObservationCaptureMode.LIVE,
                    saa("10:02:00Z", 23155, "Schieni")
            );
            pipeline.settle();

            assertEquals(
                    beforeSignals + 1,
                    pipeline.modelInputs().size()
            );
            assertEquals(
                    "BODY_SIGNALS_FOUND",
                    pipeline.modelFacingKinds().getLast()
            );
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    ),
                    "the historical reading recorded nothing"
            );
            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    "and the live one recorded itself"
            );
        }
    }

    /**
     * D3: a historical arrival still opens the visit it describes.
     *
     * <p>Where the Commander is is true whatever the capture mode. The visit
     * boundary is followed; only what the model was told is not.</p>
     */
    @Test
    void aBootstrapArrivalStillOpensTheVisit(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            pipeline.journal(
                    ObservationCaptureMode.BOOTSTRAP,
                    loadGame("10:00:00Z")
            );
            pipeline.journal(
                    ObservationCaptureMode.BOOTSTRAP,
                    jump("10:00:01Z", 23155, "Schieni")
            );
            pipeline.journal(
                    ObservationCaptureMode.LIVE,
                    fss("10:01:00Z", 23155, "Schieni")
            );
            pipeline.settle();
            int afterFirstReading = pipeline.modelInputs().size();

            pipeline.journal(
                    ObservationCaptureMode.LIVE,
                    fss("10:02:00Z", 23155, "Schieni")
            );
            pipeline.settle();

            assertEquals(
                    1,
                    afterFirstReading,
                    "only the live reading reached the provider"
            );
            assertEquals(
                    afterFirstReading,
                    pipeline.modelInputs().size(),
                    "the visit the bootstrap jump opened is still the visit, "
                            + "so the repeat is a repeat"
            );
        }
    }

    // ------------------------------------------- E. graph/observer agreement

    /**
     * E: five outcomes, each checked on the graph and on the model at once.
     *
     * <p>Occurrence and model-facing event exist together or not at all, and no
     * deduplicated batch ever reaches the provider empty. A historical reading
     * and a reading of nothing are the two that mutate canonical state and
     * nothing else.</p>
     */
    @Test
    void theGraphAndTheObserverAgreeOnEveryOutcome(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrivedIn(pipeline, "Schieni", 23155, "10:00:01Z");
            // A selected body, so the canonical counts can be read out.
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:20Z","event":"ApproachBody",
                     "StarSystem":"Schieni","SystemAddress":23155,
                     "Body":"Schieni 4 a","BodyID":20}
                    """);
            pipeline.settle();
            int afterArrival = pipeline.modelInputs().size();

            pipeline.journal(
                    ObservationCaptureMode.BOOTSTRAP,
                    saa("10:00:30Z", 23155, "Schieni")
            );
            pipeline.settle();
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    "a historical reading is not structural"
            );
            assertEquals(
                    afterArrival,
                    pipeline.modelInputs().size(),
                    "and it is not reported"
            );
            assertEquals(
                    1,
                    pipeline.establishedBody(23155L, 20L)
                            .biologicalSignalCount(),
                    "but what it established is restored"
            );

            pipeline.journal(fss("10:01:00Z", 23155, "Schieni"));
            pipeline.settle();
            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.FSS_BODY_SIGNALS_FOUND
                    ),
                    "a new result is structural"
            );
            assertEquals(
                    afterArrival + 1,
                    pipeline.modelInputs().size(),
                    "and it is reported"
            );

            pipeline.journal(saa("10:02:00Z", 23155, "Schieni"));
            pipeline.settle();
            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    "the other instrument's reading is its own finding"
            );
            assertEquals(
                    afterArrival + 2,
                    pipeline.modelInputs().size(),
                    "and it is reported for itself"
            );

            pipeline.journal(saaOf("10:02:30Z", 23155, "Schieni", BIO_ZERO));
            pipeline.settle();
            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    "a reading of nothing is not structural, so the count is "
                            + "still the one real survey"
            );
            assertEquals(
                    afterArrival + 2,
                    pipeline.modelInputs().size(),
                    "and it is not reported"
            );
            assertEquals(
                    1,
                    pipeline.establishedBody(23155L, 20L)
                            .biologicalSignalCount(),
                    "and it retracts nothing"
            );

            pipeline.journal(saaChanged("10:03:00Z", 23155, "Schieni"));
            pipeline.settle();
            assertEquals(
                    2L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SAA_SIGNALS_FOUND
                    ),
                    "a changed result is structural"
            );
            assertEquals(
                    afterArrival + 3,
                    pipeline.modelInputs().size(),
                    "and it is reported too"
            );
            assertEquals(
                    List.of(
                            "BODY_SIGNALS_FOUND",
                            "BODY_SIGNALS_FOUND",
                            "BODY_SIGNALS_FOUND"
                    ),
                    pipeline.modelFacingKinds()
                            .subList(afterArrival, afterArrival + 3),
                    "the system scan, the survey and the changed survey"
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private static DecisionProductionPipeline perTrigger(Path directory) {
        return new DecisionProductionPipeline(
                directory,
                new DecisionTurnPolicy(1, 16_000)
        );
    }

    private static void arrivedIn(
            DecisionProductionPipeline pipeline,
            String system,
            long address,
            String time
    ) throws Exception {
        pipeline.journal(loadGame("10:00:00Z"));
        pipeline.journal(jump(time, address, system));
        pipeline.settle();
    }

    private static void jumpTo(
            DecisionProductionPipeline pipeline,
            String system,
            long address,
            String time
    ) throws Exception {
        pipeline.journal(jump(time, address, system));
        pipeline.settle();
    }

    private static String loadGame(String time) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"LoadGame\",\"FID\":\"F12345678\","
                + "\"ShipID\":9,\"Ship\":\"explorer_nx\","
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

    private static String fss(String time, long address, String system) {
        return signals(time, "FSSBodySignals", address, system, BIO_1);
    }

    private static String saa(String time, long address, String system) {
        return signals(time, "SAASignalsFound", address, system, BIO_1);
    }

    private static String saaChanged(
            String time,
            long address,
            String system
    ) {
        return signals(time, "SAASignalsFound", address, system, BIO_1_GEO_2);
    }

    private static String saaOf(
            String time,
            long address,
            String system,
            String reported
    ) {
        return signals(time, "SAASignalsFound", address, system, reported);
    }

    private static final String BIO_1 =
            "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":1}";
    private static final String BIO_ZERO =
            "{\"Type\":\"$SAA_SignalType_Biological;\",\"Count\":0}";
    private static final String BIO_1_GEO_2 = BIO_1
            + ",{\"Type\":\"$SAA_SignalType_Geological;\",\"Count\":2}";

    private static String signals(
            String time,
            String eventName,
            long address,
            String system,
            String reported
    ) {
        return "{\"timestamp\":\"2026-07-30T" + time + "\",\"event\":\""
                + eventName + "\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"BodyID\":20,\"BodyName\":\"" + system
                + " 4 a\",\"Signals\":[" + reported + "]}";
    }
}
