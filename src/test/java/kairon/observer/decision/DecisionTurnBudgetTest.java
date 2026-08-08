package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.llm.ObserverResponseValidator;
import kairon.turn.overflow.ContextOverflow;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when a turn will not fit.
 *
 * <p>One rung, then a closed door. The selected context can go, because it was
 * chosen as the part the events could be understood without. Events and their
 * exact changes cannot, so a turn that still does not fit produces no request
 * at all rather than a smaller and less true one.</p>
 */
final class DecisionTurnBudgetTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    @Test
    void aFittingTurnIsSentWholeAndSaysNothingAboutTheBudget() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        LlmDecisionRequestCompactor compactor = compactor(
                DecisionTurnPolicy.production()
        );

        LlmDecisionRequestCompactor.Result.Fitted fitted =
                assertInstanceOf(
                        LlmDecisionRequestCompactor.Result.Fitted.class,
                        compactor.prepare(fixture.inputs(List.of(
                                approach(fixture)
                        )))
                );

        assertFalse(fitted.compactionApplied());
        assertFalse(fitted.request().contextIncomplete());
        assertFalse(fitted.serializedJson().contains("contextIncomplete"));
    }

    @Test
    void aTightBudgetDropsTheContextAndSaysSo() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        DecisionTurnInputs inputs = landingAfterAnEarlierTurn(fixture);
        int mandatory = compactor(DecisionTurnPolicy.production())
                .mandatoryCharacterCount(inputs);
        int full = serializer.serialize(
                factory.create(inputs)
        ).length();
        assertTrue(
                mandatory < full,
                "the fixture must actually carry a droppable context"
        );

        LlmDecisionRequestCompactor.Result.Fitted fitted =
                assertInstanceOf(
                        LlmDecisionRequestCompactor.Result.Fitted.class,
                        compactor(new DecisionTurnPolicy(8, full - 1))
                                .prepare(inputs)
                );

        assertTrue(fitted.compactionApplied());
        assertTrue(fitted.request().context().isEmpty());
        assertTrue(fitted.request().contextIncomplete());
        assertFalse(fitted.request().events().isEmpty());
        assertEquals(full, fitted.originalCharacterCount());
    }

    /**
     * Compaction never leaves an event without its own account of itself.
     *
     * <p>The one rung is the context, and the events pass through it
     * untouched. Fields with nothing to attach them to would be worse than the
     * loss compaction is trying to avoid, so the description is mandatory in
     * the same sense the fields are.</p>
     */
    @Test
    void compactionKeepsEveryEventsDescription() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        DecisionTurnInputs inputs = landingAfterAnEarlierTurn(fixture);
        int full = serializer.serialize(
                factory.create(inputs)
        ).length();

        LlmDecisionRequestCompactor.Result.Fitted fitted =
                assertInstanceOf(
                        LlmDecisionRequestCompactor.Result.Fitted.class,
                        compactor(new DecisionTurnPolicy(8, full - 1))
                                .prepare(inputs)
                );

        assertTrue(fitted.compactionApplied());
        for (LlmDecisionRequest.Event event : fitted.request().events()) {
            assertFalse(event.description().isBlank());
        }
        assertTrue(
                fitted.serializedJson().contains(
                        "\"event\":\"The Commander's ship landed on "
                                + "the surface of a planet or moon.\""),
                fitted.serializedJson()
        );
        assertFalse(fitted.serializedJson().contains("\"kind\":\"TOUCHDOWN"));
    }

    @Test
    void mandatoryContentThatDoesNotFitRefusesRatherThanShrinks() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        DecisionTurnInputs inputs = fixture.inputs(List.of(
                approach(fixture)
        ));

        LlmDecisionRequestCompactor.Result.DoesNotFit failure =
                assertInstanceOf(
                        LlmDecisionRequestCompactor.Result.DoesNotFit.class,
                        compactor(new DecisionTurnPolicy(8, 40))
                                .prepare(inputs)
                );

        assertEquals(40, failure.configuredCharacterBudget());
        assertTrue(failure.overshootCharacters() > 0);
        List<LlmDecisionRequestCompactor.Result.SectionWeight> sections =
                failure.largestMandatorySections();
        assertEquals(
                Set.of(
                        DecisionSections.EVENTS,
                        DecisionSections.CHANGES
                ),
                sections.stream()
                        .map(LlmDecisionRequestCompactor.Result.SectionWeight
                                ::section)
                        .collect(java.util.stream.Collectors.toSet()),
                "the report names every mandatory section"
        );
        assertTrue(
                sections.getFirst().characterCount()
                        >= sections.getLast().characterCount(),
                "largest first, so the report says what dominated"
        );
        assertEquals(
                failure.contextOverflow().overshootCharacters(),
                failure.overshootCharacters()
        );
    }

    /**
     * The whole turn lifecycle when the budget refuses.
     *
     * <p>No provider call, no comment, no synthesised silence, no speech, and
     * the batch is consumed exactly once — its triggers are not returned to the
     * queue, because a later turn's evidence cannot contain them.</p>
     */
    @Test
    void anOverflowTurnCallsNothingAndIsRecordedAsSuch(
            @TempDir Path directory
    ) throws Exception {
        Path trace = directory.resolve("decision-pipeline-turns.jsonl");
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(
                             directory,
                             new DecisionTurnPolicy(8, 40)
                     )) {
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:00Z",
                     "event":"ApproachBody","StarSystem":"Overflow",
                     "SystemAddress":9001,"Body":"Overflow 1","BodyID":1}
                    """);
            pipeline.replayExhausted("2026-07-30T10:00:01Z");
            pipeline.settle();

            assertEquals(
                    List.of(),
                    pipeline.modelInputs(),
                    "the provider is never reached"
            );
            assertEquals(List.of(), pipeline.deliveredComments());
            assertEquals(1, pipeline.decisions().size());
            assertEquals(
                    ObserverResponseValidator.Status.CONTEXT_TOO_LARGE,
                    pipeline.decisions().getFirst().validatedResponse().status()
            );
            assertEquals(
                    0,
                    pipeline.observer().snapshot().toCompletableFuture()
                            .join().previousComments().size(),
                    "previous-comment memory is untouched"
            );

            List<String> lines =
                    Files.readAllLines(trace, StandardCharsets.UTF_8);
            assertEquals(1, lines.size(), "the batch is consumed once");
            JsonNode line = JSON.readTree(lines.getFirst());
            assertEquals(
                    "CONTEXT_TOO_LARGE",
                    line.path("turnOutcome").textValue()
            );
            assertFalse(line.path("providerInvoked").booleanValue());
            assertFalse(line.path("commentDelivered").booleanValue());
            assertFalse(line.path("speechInvoked").booleanValue());
            assertTrue(line.path("situationTurn").isNull());
            assertTrue(line.path("modelInput").isNull());
            assertEquals(
                    1,
                    line.path("triggerBusSequences").size(),
                    "a turn that never reached the provider still records "
                            + "which observations it was built from"
            );
            assertTrue(
                    line.path("contextOverflow")
                            .path("overshootCharacters")
                            .intValue() > 0
            );
        }
    }

    private LlmDecisionRequestCompactor compactor(DecisionTurnPolicy policy) {
        return new LlmDecisionRequestCompactor(factory, serializer, policy);
    }

    /**
     * A turn whose body facts come from state rather than from a change.
     *
     * <p>The survey is drained into an earlier turn, so by the time the landing
     * happens the body is known but nothing about it is changing — which is
     * exactly when the context has something to contribute.</p>
     */
    private static DecisionTurnInputs landingAfterAnEarlierTurn(
            DecisionTurnFixture fixture
    ) {
        fixture.inputs(List.of(approach(fixture)));
        return fixture.inputs(List.of(fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:02Z","event":"Touchdown",
                 "PlayerControlled":true,"Latitude":18.7,"Longitude":-35.0}
                """)));
    }

    private static ProjectedObservation approach(
            DecisionTurnFixture fixture
    ) {
        fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Scan",
                 "SystemAddress":23155,"BodyID":20,"BodyName":"Budget Body",
                 "PlanetClass":"Icy body","Landable":true,
                 "WasDiscovered":false,"WasMapped":false,
                 "DistanceFromArrivalLS":1216.6}
                """);
        return fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"ApproachBody",
                 "StarSystem":"Budget System","SystemAddress":23155,
                 "Body":"Budget Body","BodyID":20}
                """);
    }
}
