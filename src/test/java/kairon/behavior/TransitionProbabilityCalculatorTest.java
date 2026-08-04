package kairon.behavior;

import kairon.behavior.context.TransitionContextKeyFactory;
import kairon.behavior.graph.TransitionProbabilityCalculator;
import kairon.behavior.model.ContextKey;
import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.NextEventPrediction;
import kairon.behavior.model.OccurrenceTransition;
import kairon.behavior.model.PredictionBasis;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.TransitionOccurrenceId;
import kairon.behavior.normalize.NormalizedEventType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TransitionProbabilityCalculatorTest {

    private static final Duration HALF_LIFE = Duration.ofDays(30);
    private static final Instant EVENT_TIME =
            Instant.parse("2026-01-01T00:00:00Z");
    private static final GraphId GRAPH_ID = new GraphId("F12345678", 9);
    private static final SystemEpisodeId EPISODE_ID =
            new SystemEpisodeId("episode-1");
    private static final TransitionContextKeyFactory CONTEXT_KEYS =
            new TransitionContextKeyFactory();
    private static final TransitionProbabilityCalculator CALCULATOR =
            new TransitionProbabilityCalculator(HALF_LIFE, 2.0, CONTEXT_KEYS);

    @Test
    void contextKeysSeparateSignalBucketsAndUseCanonicalTouchdownValues() {
        ContextKey oneSignal = CONTEXT_KEYS.create(
                NormalizedEventType.SAA_SIGNALS_FOUND,
                BehaviorGraphModelTest.context(1, true, null, true)
        );
        ContextKey sevenSignals = CONTEXT_KEYS.create(
                NormalizedEventType.SAA_SIGNALS_FOUND,
                BehaviorGraphModelTest.context(7, true, null, true)
        );
        ContextKey touchdown = CONTEXT_KEYS.create(
                NormalizedEventType.TOUCHDOWN,
                BehaviorGraphModelTest.context(null, null, "Nomad", true)
        );

        assertEquals("bioSignals=1|landable=true", oneSignal.canonical());
        assertEquals("bioSignals=7|landable=true", sevenSignals.canonical());
        assertTrue(!oneSignal.equals(sevenSignals));
        assertEquals(
                "vehicle=SLV|bodyHasBiology=true",
                touchdown.canonical(),
                "a Nomad recorded under the class it used to have still counts "
                        + "in the bucket that class always described"
        );
    }

    @Test
    void repeatedContextualTransitionsRaiseOnlyMatchingPrediction() {
        ContextSnapshot sevenSignalContext =
                BehaviorGraphModelTest.context(7, true, null, true);
        ContextSnapshot oneSignalContext =
                BehaviorGraphModelTest.context(1, true, null, true);
        ContextKey sevenSignals = CONTEXT_KEYS.create(
                NormalizedEventType.SAA_SIGNALS_FOUND,
                sevenSignalContext
        );
        ContextKey oneSignal = CONTEXT_KEYS.create(
                NormalizedEventType.SAA_SIGNALS_FOUND,
                oneSignalContext
        );
        ShipBehaviorGraph graph = emptyGraph();
        for (int index = 0; index < 6; index++) {
            graph = record(
                    graph,
                    "approach-" + index,
                    NormalizedEventType.APPROACH_BODY,
                    sevenSignals,
                    EVENT_TIME.plusSeconds(index)
            );
        }
        for (int index = 0; index < 2; index++) {
            graph = record(
                    graph,
                    "jump-" + index,
                    NormalizedEventType.HYPERSPACE_JUMP_STARTED,
                    oneSignal,
                    EVENT_TIME.plusSeconds(10 + index)
            );
        }

        List<NextEventPrediction> sevenSignalPredictions =
                CALCULATOR.predict(
                        graph,
                        cursor(),
                        sevenSignalContext,
                        EVENT_TIME.plusSeconds(20),
                        10
                );
        List<NextEventPrediction> oneSignalPredictions =
                CALCULATOR.predict(
                        graph,
                        cursor(),
                        oneSignalContext,
                        EVENT_TIME.plusSeconds(20),
                        10
                );

        NextEventPrediction approachWithSeven = prediction(
                sevenSignalPredictions,
                NormalizedEventType.APPROACH_BODY
        );
        NextEventPrediction approachWithOne = prediction(
                oneSignalPredictions,
                NormalizedEventType.APPROACH_BODY
        );
        assertEquals(PredictionBasis.CONTEXTUAL, approachWithSeven.basis());
        assertTrue(
                approachWithSeven.probability()
                        > approachWithSeven.globalProbability()
        );
        assertTrue(
                approachWithSeven.probability()
                        > approachWithOne.probability()
        );
    }

    @Test
    void globalPriorKeepsUnseenContextualBranchPossible() {
        ContextSnapshot sevenSignalContext =
                BehaviorGraphModelTest.context(7, true, null, true);
        ContextKey sevenSignals = CONTEXT_KEYS.create(
                NormalizedEventType.SAA_SIGNALS_FOUND,
                sevenSignalContext
        );
        ContextKey oneSignal = CONTEXT_KEYS.create(
                NormalizedEventType.SAA_SIGNALS_FOUND,
                BehaviorGraphModelTest.context(1, true, null, true)
        );
        ShipBehaviorGraph graph = record(
                emptyGraph(),
                "approach",
                NormalizedEventType.APPROACH_BODY,
                sevenSignals,
                EVENT_TIME
        );
        graph = record(
                graph,
                "jump",
                NormalizedEventType.HYPERSPACE_JUMP_STARTED,
                oneSignal,
                EVENT_TIME
        );

        List<NextEventPrediction> predictions = CALCULATOR.predict(
                graph,
                cursor(),
                sevenSignalContext,
                EVENT_TIME,
                10
        );

        assertEquals(2, predictions.size());
        assertEquals(
                1.0,
                predictions.stream()
                        .mapToDouble(NextEventPrediction::probability)
                        .sum(),
                1.0e-12
        );
        NextEventPrediction unseenContextBranch = prediction(
                predictions,
                NormalizedEventType.HYPERSPACE_JUMP_STARTED
        );
        assertTrue(unseenContextBranch.probability() > 0.0);
        assertEquals(PredictionBasis.CONTEXTUAL, unseenContextBranch.basis());
    }

    @Test
    void missingContextEvidenceFallsBackExactlyToGlobalPrediction() {
        ContextKey oneSignal = CONTEXT_KEYS.create(
                NormalizedEventType.SAA_SIGNALS_FOUND,
                BehaviorGraphModelTest.context(1, true, null, true)
        );
        ContextKey sevenSignals = CONTEXT_KEYS.create(
                NormalizedEventType.SAA_SIGNALS_FOUND,
                BehaviorGraphModelTest.context(7, true, null, true)
        );
        ShipBehaviorGraph graph = record(
                emptyGraph(),
                "approach-1",
                NormalizedEventType.APPROACH_BODY,
                sevenSignals,
                EVENT_TIME
        );
        graph = record(
                graph,
                "approach-2",
                NormalizedEventType.APPROACH_BODY,
                sevenSignals,
                EVENT_TIME
        );
        graph = record(
                graph,
                "jump",
                NormalizedEventType.HYPERSPACE_JUMP_STARTED,
                oneSignal,
                EVENT_TIME
        );

        List<NextEventPrediction> predictions = CALCULATOR.predict(
                graph,
                cursor(),
                BehaviorGraphModelTest.context(3, true, null, true),
                EVENT_TIME,
                10
        );

        assertEquals(2, predictions.size());
        for (NextEventPrediction prediction : predictions) {
            assertEquals(PredictionBasis.GLOBAL, prediction.basis());
            assertEquals(
                    prediction.globalProbability(),
                    prediction.probability(),
                    1.0e-12
            );
            assertEquals(0.0, prediction.contextSupport(), 1.0e-12);
        }
        assertEquals(
                2.0 / 3.0,
                prediction(predictions, NormalizedEventType.APPROACH_BODY)
                        .probability(),
                1.0e-12
        );
    }

    private static ShipBehaviorGraph emptyGraph() {
        return ShipBehaviorGraph.empty(
                GRAPH_ID,
                "diamondback_explorer",
                "Kairon",
                "loadout-a"
        );
    }

    private static ShipBehaviorGraph record(
            ShipBehaviorGraph graph,
            String id,
            NormalizedEventType to,
            ContextKey contextKey,
            Instant observedAt
    ) {
        return graph.recordTransition(
                new OccurrenceTransition(
                        new TransitionOccurrenceId(id),
                        EPISODE_ID,
                        new EventOccurrenceId("from-" + id),
                        new EventOccurrenceId("to-" + id),
                        NormalizedEventType.SAA_SIGNALS_FOUND,
                        to,
                        observedAt,
                        contextKey
                ),
                HALF_LIFE
        );
    }

    private static GraphCursor cursor() {
        return new GraphCursor(
                GRAPH_ID,
                EPISODE_ID,
                new EventOccurrenceId("current"),
                NormalizedEventType.SAA_SIGNALS_FOUND,
                EVENT_TIME
        );
    }

    private static NextEventPrediction prediction(
            List<NextEventPrediction> predictions,
            NormalizedEventType type
    ) {
        return predictions.stream()
                .filter(prediction ->
                        prediction.predictedEventType().equals(type))
                .findFirst()
                .orElseThrow();
    }
}
