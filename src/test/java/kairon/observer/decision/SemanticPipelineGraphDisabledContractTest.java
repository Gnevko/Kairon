package kairon.observer.decision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static kairon.observer.decision.SemanticPipelineAssertions
        .assertGraphDisabledParity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the model is told must not depend on whether the graph is running.
 *
 * <p>The behaviour graph is a subscriber-owned projection. It contributes
 * history — what led here, what usually follows, how often this has happened at
 * this body — and nothing else. Which events the model is shown, what they
 * factually say, what changed, what is standing and how many times the provider
 * was asked belong to the projection and the observer, and a Commander who
 * turned the graph off must still get the same reading of the same events.</p>
 *
 * <p>Until now nothing tested this: every decision test wired a graph, so a
 * dependency on it could have been introduced without anything going red.</p>
 */
final class SemanticPipelineGraphDisabledContractTest {

    /** The graph really is absent, and the pipeline still runs. */
    @Test
    void aPipelineWithoutAGraphStillProjectsAndDecides(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness = SemanticPipelineHarness.create(
                directory,
                SemanticPipelineHarness.HarnessOptions.withoutGraph()
        )) {
            run(harness);
            PipelineTrace trace = harness.trace();

            assertFalse(trace.graphEnabled());
            assertEquals(List.of(), trace.episodes());
            assertEquals(List.of(), trace.occurrences());
            assertTrue(trace.cursor().isEmpty());
            assertEquals(
                    List.of(
                            "SYSTEM_JUMP",
                            "BODY_APPROACHED",
                            "BODY_SIGNALS_FOUND",
                            "MESSAGE_RECEIVED"
                    ),
                    trace.modelFacingKinds(),
                    "the observer decides what the model sees, not the graph"
            );
            for (PipelineTrace.TurnView turn : trace.turns()) {
            }
        }
    }

    /** The same sequence, both ways, differing only in what the graph adds. */
    @Test
    void theSameSequenceSaysTheSameThingWithoutAGraph(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness withGraph = SemanticPipelineHarness.create(
                directory.resolve("with-graph")
        );
                SemanticPipelineHarness withoutGraph =
                        SemanticPipelineHarness.create(
                                directory.resolve("without-graph"),
                                SemanticPipelineHarness.HarnessOptions
                                        .withoutGraph()
                        )) {
            run(withGraph);
            run(withoutGraph);

            assertGraphDisabledParity(
                    withGraph.trace(),
                    withoutGraph.trace()
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    /**
     * One small sequence covering the three shapes that matter.
     *
     * <p>A structural arrival, a structural finding with a body behind it, and
     * a conversational event that owes the graph nothing. Each closes its own
     * batch so a difference in one turn cannot be absorbed by another.</p>
     */
    private static void run(SemanticPipelineHarness harness) {
        harness.journal("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """)
                .journal("""
                        {"timestamp":"2026-07-30T10:00:01Z","event":"FSDJump",
                         "StarSystem":"Schieni","SystemAddress":23155,
                         "JumpDist":8.5,"FuelUsed":0.4,"FuelLevel":30.2}
                        """)
                .closeBatch();
        harness.journal("""
                {"timestamp":"2026-07-30T10:00:30Z","event":"ApproachBody",
                 "StarSystem":"Schieni","SystemAddress":23155,
                 "Body":"Schieni 4 a","BodyID":20}
                """)
                .closeBatch();
        harness.journal("""
                {"timestamp":"2026-07-30T10:01:00Z","event":"FSSBodySignals",
                 "StarSystem":"Schieni","SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni 4 a",
                 "Signals":[{"Type":"$SAA_SignalType_Biological;","Count":1}]}
                """)
                .closeBatch();
        harness.journal("""
                {"timestamp":"2026-07-30T10:02:00Z","event":"ReceiveText",
                 "Channel":"player","From":"Ana","Message":"see you there",
                 "Message_Localised":"see you there"}
                """)
                .closeBatch();
    }
}
