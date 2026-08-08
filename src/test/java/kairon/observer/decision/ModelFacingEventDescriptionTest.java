package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the provider is told an event is, end to end.
 *
 * <p>The description is asked of the observation the pipeline actually
 * produced, so this is a pipeline test rather than a projection one: an
 * assertion about the serialized request with no assertion about which
 * observation produced it would not notice an event describing its
 * neighbour.</p>
 */
final class ModelFacingEventDescriptionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /**
     * The provider is told what happened and never Kairon's name for it.
     *
     * <p>Both halves matter. Sending the description beside the kind would be
     * two answers to one question, in a vocabulary only this process shares.
     * </p>
     */
    @Test
    void aCurrentEventCarriesItsDescriptionAndNoInternalKind()
            throws Exception {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        String serialized = serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"Touchdown","PlayerControlled":true,
                         "StarSystem":"Schieni","Body":"Schieni 4 a"}
                        """)))
        ));

        JsonNode event = JSON.readTree(serialized).path("events").get(0);
        assertEquals(
                "The Commander's ship landed on the surface of a planet or moon.",
                event.path("event").textValue()
        );
        assertFalse(event.has("kind"), serialized);
        assertFalse(serialized.contains("TOUCHDOWN"), serialized);
    }

    /** The description opens the event and precedes its own fields. */
    @Test
    void theDescriptionOpensTheEventAndPrecedesEveryField() throws Exception {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        String serialized = serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"Touchdown","PlayerControlled":true,
                         "StarSystem":"Schieni","Body":"Schieni 4 a"}
                        """)))
        ));

        List<String> names = new ArrayList<>();
        JSON.readTree(serialized).path("events").get(0)
                .fieldNames().forEachRemaining(names::add);
        assertEquals(
                List.of("event", "commanderControlled"),
                names,
                serialized
        );
    }

    /**
     * The structured fields are untouched by the change.
     *
     * <p>The description states the kind of thing that happened; what it
     * happened to stays exactly where it was, under the same names and in the
     * same order.</p>
     */
    @Test
    void everyStructuredFieldSurvivesUnchanged() throws Exception {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        String serialized = serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"SAAScanComplete","BodyName":"Schieni 4 a",
                         "SystemAddress":1,"BodyID":4,"ProbesUsed":2,
                         "EfficiencyTarget":3}
                        """)))
        ));

        assertEquals("""
                {"events":[{\
                "event":"A surface area analysis scan of a body was \
                completed.",\
                "body":"Schieni 4 a","efficiencyTarget":3,"probesUsed":2}]}""",
                serialized);
    }

    /**
     * Every event of a batch is described by its own record.
     *
     * <p>Two readings of one body, two instruments, one batch — and the second
     * sentence is not the first repeated. A shared kind is exactly where a
     * single lookup would have given one answer for both, and the batch is
     * closed explicitly so that "one batch" is a fact rather than a side effect
     * of a policy bound.</p>
     */
    @Test
    void eachEventOfABatchIsDescribedByItsOwnInstance(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(jump())
                    .closeBatch();

            harness.journal(signals("10:01:00Z", "FSSBodySignals",
                            "{\"Type\":\"$SAA_SignalType_Biological;\","
                                    + "\"Count\":1}"))
                    .journal(signals("10:01:01Z", "SAASignalsFound",
                            "{\"Type\":\"$SAA_SignalType_Biological;\","
                                    + "\"Count\":1},"
                                    + "{\"Type\":"
                                    + "\"$SAA_SignalType_Geological;\","
                                    + "\"Count\":2}"))
                    .closeBatch();

            PipelineTrace.TurnView turn = harness.trace().turns().getLast();
            assertEquals(
                    List.of(
                            "A full spectrum system scan reported signal "
                                    + "data for a body.",
                            "A surface area analysis scan reported "
                                    + "signal data for a planet or rings."
                    ),
                    turn.eventDescriptions(),
                    turn.userMessage()
            );
            assertEquals(
                    List.of("BODY_SIGNALS_FOUND", "BODY_SIGNALS_FOUND"),
                    turn.eventKinds(),
                    "one internal kind, two records, two sentences"
            );
            assertEquals(List.of(1, 2), turn.eventIds());
            assertFalse(turn.userMessage().contains("BODY_SIGNALS_FOUND"),
                    turn.userMessage());
        }
    }

    /**
     * A trigger that cannot describe itself fails the turn.
     *
     * <p>No fallback exists and none may be added: the internal kind in the one
     * slot that says what happened is the defect this contract removed. The
     * projection throws, and the coordinator's existing failure path is what
     * keeps the provider out of it.</p>
     */
    @Test
    void anEventThatCannotDescribeItselfIsRefusedRatherThanNamed() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        ProjectedObservation trigger = fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Touchdown",
                 "PlayerControlled":true}
                """);
        PublishedObservation<?> published = trigger.trigger();
        ProjectedObservation silent = new ProjectedObservation(
                new PublishedObservation<>(
                        published.observationId(),
                        published.busSequence(),
                        published.source(),
                        published.sourcePosition(),
                        published.sourceTime(),
                        published.observedAt(),
                        published.captureMode(),
                        published.schemaVersion(),
                        new SilentEvent(
                                ((JournalEventObservation) published.payload())
                                        .raw()
                        )
                ),
                trigger.applied(),
                trigger.stateChanges(),
                trigger.graphResult(),
                trigger.behaviorSituation(),
                trigger.semanticEnvelope(),
                trigger.systemRegistry()
        );

        IllegalStateException refusal = assertThrows(
                IllegalStateException.class,
                () -> new DecisionEventProjector().project(1, silent)
        );
        assertTrue(
                refusal.getMessage().contains("model trigger"),
                refusal.getMessage()
        );
        assertFalse(
                refusal.getMessage().contains("TOUCHDOWN"),
                "the internal kind is never offered as a substitute"
        );
    }

    /** A request cannot be assembled without a description at all. */
    @Test
    void anEventWithoutADescriptionCannotBeBuilt() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LlmDecisionRequest.Event(1, "TOUCHDOWN", " ",
                        List.of())
        );
        assertThrows(
                NullPointerException.class,
                () -> new LlmDecisionRequest.Event(1, "TOUCHDOWN", null,
                        List.of())
        );
    }

    private static String loadGame() {
        return "{\"timestamp\":\"2026-07-30T10:00:00Z\","
                + "\"event\":\"LoadGame\",\"FID\":\"F12345678\","
                + "\"ShipID\":9,\"Ship\":\"explorer_nx\","
                + "\"ShipName\":\"Wanderer\"}";
    }

    private static String jump() {
        return "{\"timestamp\":\"2026-07-30T10:00:01Z\","
                + "\"event\":\"FSDJump\",\"StarSystem\":\"Schieni\","
                + "\"SystemAddress\":23155,\"JumpDist\":8.5,"
                + "\"FuelUsed\":0.4,\"FuelLevel\":30.2}";
    }

    private static String signals(
            String time,
            String eventName,
            String signals
    ) {
        return "{\"timestamp\":\"2026-07-30T" + time + "\",\"event\":\""
                + eventName + "\",\"StarSystem\":\"Schieni\","
                + "\"SystemAddress\":23155,\"BodyID\":20,"
                + "\"BodyName\":\"Schieni 4 a\",\"Signals\":["
                + signals + "]}";
    }

    /** A journal payload that is outside the model-facing contract. */
    private record SilentEvent(RawJournalData raw)
            implements JournalEventObservation {
    }
}
