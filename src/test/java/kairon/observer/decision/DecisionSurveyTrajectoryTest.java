package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.graph.BehaviorGraphApplyStatus;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.behavior.snapshot.ActiveEpisodeSituation;
import kairon.behavior.snapshot.SituationOccurrence;
import kairon.observation.journal.event.exploration.SAAScanComplete;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A completed surface survey is something the Commander did.
 *
 * <p>Mapping a body is a deliberate multi-step action, and finishing it is the
 * end of that action — so it belongs in the run of events a later turn is read
 * against. Before this it was treated as background, and a landing approach
 * arrived with no memory of the survey that had just been completed on the very
 * body being approached.</p>
 *
 * <p>Everything here runs the production parser, projector and behavior graph
 * against isolated temporary storage. The provider is a stub that cannot
 * influence what is built.</p>
 */
final class DecisionSurveyTrajectoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /** The survey is applied to the graph rather than passed over. */
    @Test
    void aCompletedSurveyIsAppliedToTheGraph(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            survey(pipeline);

            ProjectedObservation applied = triggerOf(
                    pipeline,
                    SAAScanComplete.class.getSimpleName()
            );
            assertEquals(
                    BehaviorGraphApplyStatus.APPLIED,
                    applied.graphResult().status(),
                    "the survey used to be reported NOT_APPLICABLE"
            );
            assertEquals(
                    NormalizedEventType.SAA_SCAN_COMPLETE,
                    applied.behaviorSituation()
                            .activeEpisode()
                            .orElseThrow()
                            .cursor()
                            .eventType(),
                    "it owns the cursor like any other structural occurrence"
            );
        }
    }

    /**
     * The episode remembers both survey events, in source order.
     *
     * <p>The completion, the signals record and the detailed scan share one
     * journal timestamp in this fixture, exactly as they do in the game. Order
     * therefore has to come from the order the observations arrived, not from
     * the clock.</p>
     */
    @Test
    void theEpisodeKeepsBothSurveyEventsInSourceOrder(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            survey(pipeline);

            List<ProjectedObservation> triggers = pipeline.capturedTriggers();
            ActiveEpisodeSituation episode = triggers.getLast()
                    .behaviorSituation()
                    .activeEpisode()
                    .orElseThrow();

            assertEquals(
                    List.of(
                            // The session was restored here, so the visit
                            // opens with what the Commander did next.
                            NormalizedEventType.SUPERCRUISE_JUMP_STARTED,
                            NormalizedEventType.SUPERCRUISE_ENTRY,
                            NormalizedEventType.SAA_SCAN_COMPLETE,
                            NormalizedEventType.SAA_SIGNALS_FOUND,
                            NormalizedEventType.BODY_SCANNED,
                            NormalizedEventType.APPROACH_BODY
                    ),
                    episode.trajectory().stream()
                            .map(SituationOccurrence::eventType)
                            .toList()
            );
            assertEquals(
                    List.of(0L, 1L, 2L, 3L, 4L, 5L),
                    episode.trajectory().stream()
                            .map(SituationOccurrence::episodeSequence)
                            .toList(),
                    "contiguous positions, so the two survey events cannot swap"
            );
            assertEquals(
                    1L,
                    episode.occurrenceCounts()
                            .get(NormalizedEventType.SAA_SCAN_COMPLETE),
                    "the completion is not merged into the signals record"
            );
            assertEquals(
                    1L,
                    episode.occurrenceCounts()
                            .get(NormalizedEventType.SAA_SIGNALS_FOUND)
            );
        }
    }

    /**
     * The detailed scan is a result the Commander went and got.
     *
     * <p>It used to be treated as a body fact that happened to arrive, and a
     * whole system's worth of exploration reached the model as nothing at all.
     * One occurrence per body per visit: the record establishes the body, and
     * repeating it establishes nothing further.</p>
     */
    @Test
    void theDetailedScanCreatesExactlyOneOccurrence(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            survey(pipeline);

            ActiveEpisodeSituation episode = pipeline.capturedTriggers()
                    .getLast()
                    .behaviorSituation()
                    .activeEpisode()
                    .orElseThrow();
            assertEquals(
                    1L,
                    episode.occurrenceCounts()
                            .get(NormalizedEventType.BODY_SCANNED)
            );
            assertFalse(
                    episode.trajectory().stream()
                            .map(SituationOccurrence::eventType)
                            .anyMatch(NormalizedEventType
                                    .FSS_BODY_SIGNALS_FOUND::equals),
                    "no system scanner reported anything in this fixture"
            );
        }
    }

    /** The approach now arrives knowing the body was just mapped. */
    @Test
    void theApproachRemembersTheSurveyThatPrecededIt(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            survey(pipeline);
            JsonNode request = approachRequest(pipeline);

            assertEquals(
                    "A ship in supercruise came within a body's orbital-cruise zone.",
                    request.path("events").get(0).path("event").textValue()
            );
            assertEquals(
                    List.of(
                            "A surface area analysis scan of a body was completed.",
                            "A surface area analysis scan reported signal data for a planet or rings.",
                            "A discovery scan reported a star, planet or moon's properties."
                    ),
                    recent(request)
            );
            assertEquals(
                    1,
                    frequency(recent(request), "A surface area analysis scan of a body was completed.")
            );
            assertEquals(
                    1,
                    frequency(recent(request), "A surface area analysis scan reported signal data for a planet or "
                            + "rings.")
            );
            assertFalse(
                    recent(request).contains("A ship in supercruise came within a body's orbital-cruise zone."),
                    "the current event is not also its own predecessor"
            );
        }
    }

    /** None of the machinery that remembers it comes along. */
    @Test
    void theRememberedSurveyCarriesNoInternalIdentity(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            survey(pipeline);
            String serialized = approachRequest(pipeline).toString();

            for (String internal : List.of(
                    "SAA_SCAN_COMPLETE",
                    "SAA_SIGNALS_FOUND",
                    "SAAScanComplete",
                    "SUPERCRUISE_ENTRY",
                    "APPROACH_BODY",
                    "occurrenceId",
                    "episodeSequence",
                    "episodeId",
                    "graphId",
                    "busSequence",
                    "bgo1-",
                    "bge1-",
                    "source"
            )) {
                assertFalse(
                        serialized.contains(internal),
                        internal + " reached the provider: " + serialized
                );
            }
        }
    }

    // ------------------------------------------------------------- fixtures

    /**
     * The audited sequence, with one timestamp shared by the three events the
     * game emits together.
     */
    private static void survey(DecisionProductionPipeline pipeline)
            throws Exception {
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"Location",
                 "StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155945939738,"Docked":false}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:02Z","event":"StartJump",
                 "JumpType":"Supercruise","StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155945939738}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:03Z","event":"SupercruiseEntry",
                 "StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155945939738}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:04Z","event":"SAAScanComplete",
                 "BodyName":"Schieni GG-A c3-84 4 a",
                 "SystemAddress":23155945939738,"BodyID":20,
                 "ProbesUsed":2,"EfficiencyTarget":2}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:04Z","event":"SAASignalsFound",
                 "SystemAddress":23155945939738,"BodyID":20,
                 "BodyName":"Schieni GG-A c3-84 4 a",
                 "Signals":[{"Type":"$SAA_SignalType_Biological;",
                 "Type_Localised":"Biological","Count":1}]}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:04Z","event":"Scan",
                 "ScanType":"Detailed","SystemAddress":23155945939738,
                 "BodyID":20,"BodyName":"Schieni GG-A c3-84 4 a",
                 "PlanetClass":"Icy body","Landable":true,
                 "WasDiscovered":false,"WasMapped":false,
                 "DistanceFromArrivalLS":1081.453145}
                """);
        pipeline.journal("""
                {"timestamp":"2026-07-30T10:00:05Z","event":"ApproachBody",
                 "StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155945939738,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20}
                """);
        pipeline.settleProjection();
    }

    /**
     * The request a turn would build for the approach alone.
     *
     * <p>Everything before it is drained through earlier turns, exactly as a
     * replay would.</p>
     */
    private JsonNode approachRequest(DecisionProductionPipeline pipeline) {
        List<ProjectedObservation> triggers = pipeline.capturedTriggers();
        for (int index = 0; index < triggers.size() - 1; index++) {
            pipeline.inputsFor(List.of(triggers.get(index)));
        }
        return read(serializer.serialize(factory.create(
                pipeline.inputsFor(List.of(triggers.getLast()))
        )));
    }

    private static ProjectedObservation triggerOf(
            DecisionProductionPipeline pipeline,
            String payloadSimpleName
    ) {
        return pipeline.capturedTriggers().stream()
                .filter(projected -> projected.trigger().payload().getClass()
                        .getSimpleName().equals(payloadSimpleName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        payloadSimpleName + " never reached the projection"
                ));
    }

    private static int frequency(List<String> values, String value) {
        int count = 0;
        for (String candidate : values) {
            if (candidate.equals(value)) {
                count++;
            }
        }
        return count;
    }

    private static List<String> recent(JsonNode request) {
        List<String> recent = new ArrayList<>();
        request.path("trajectory").path("recent")
                .forEach(kind -> recent.add(kind.textValue()));
        return List.copyOf(recent);
    }

    private static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
