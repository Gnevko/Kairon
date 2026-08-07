package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.llm.DecisionPromptFactory;
import kairon.llm.LlmClient;
import kairon.llm.ObserverResponseValidator;
import kairon.llm.ObserverResponseValidator.ValidatedObserverResponse;
import kairon.observer.ObserverTurnListener;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event id exists inside Kairon and nowhere on the wire.
 *
 * <p>Two halves of one contract, which is why they are asserted together. The
 * request document identifies no event to the model, and the prompt asks it to
 * name none — a document that stopped carrying ids while the instructions still
 * asked for them would be a contract nothing could satisfy. And the numbering
 * itself is untouched: the projection still numbers events {@code 1..n}, the
 * change attribution still points at those numbers, and the trace still records
 * which observation each one stood for.</p>
 *
 * <p>Written against the real pipeline rather than a hand-built request, so what
 * is asserted is the bytes a provider would actually receive.</p>
 */
final class ProviderFacingEventIdentityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Every spelling the identity could come back under.
     *
     * <p>Checked as raw substrings of the document, not as property lookups: a
     * number nested one level deeper would still be the identity leaking.</p>
     */
    private static final List<String> REMOVED_PROVIDER_PROPERTIES = List.of(
            "\"id\"",
            "\"eventId\"",
            "\"evidence\"",
            "\"evidenceIds\"",
            "\"eventIds\"",
            "\"localId\"",
            "\"busSequence\""
    );

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    // ------------------------------------------------ what the provider sees

    /**
     * A landing is what happened and where, and carries no handle.
     *
     * <p>The events section is compared whole rather than field by field: the
     * point is that nothing precedes the description, and only an exact string
     * can say that. The rest of the document is checked for the identity
     * separately, because a landing legitimately establishes a system name and
     * a flight mode.</p>
     */
    @Test
    void aLandingIsSentAsWhatHappenedAndWhereAndNothingElse() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        LlmDecisionRequest request = factory.create(
                fixture.inputs(List.of(fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"Touchdown","PlayerControlled":true,
                         "StarSystem":"Test A","Body":"Test A 1"}
                        """)))
        );

        assertEquals(
                "{\"events\":[{\"event\":\"A ship landed on the surface "
                        + "of a planet or moon.\",\"body\":\"Test A 1\","
                        + "\"commanderControlled\":true}]}",
                serializer.serializeSection(request, DecisionSections.EVENTS)
        );
        String whole = serializer.serialize(request);
        assertFalse(whole.contains("\"id\""), whole);
        assertEquals(
                1,
                request.events().getFirst().id(),
                "the event is still numbered one inside Kairon"
        );
    }

    /**
     * No turn of a real replay identifies an event.
     *
     * <p>Every provider call of a multi-system replay, checked as text. The
     * batched shape matters as much as the single one: a two-event request is
     * where a numbering would have had something to number.</p>
     */
    @Test
    void noRequestOfARealReplayCarriesAnEventIdentity(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            replay(harness);

            PipelineTrace trace = harness.trace();
            assertTrue(trace.providerCalls() >= 2, trace.describe());
            boolean sawBatch = false;
            for (PipelineTrace.TurnView turn : trace.turns()) {
                sawBatch |= turn.events().size() > 1;
                for (String removed : REMOVED_PROVIDER_PROPERTIES) {
                    assertFalse(
                            turn.userMessage().contains(removed),
                            () -> "the request still carries " + removed
                                    + "\n" + trace.describe()
                    );
                }
                for (JsonNode event : turn.events()) {
                    assertTrue(
                            event.has("event"),
                            () -> "an event says nothing about itself\n"
                                    + trace.describe()
                    );
                }
            }
            assertTrue(
                    sawBatch,
                    () -> "no multi-event request was produced, so nothing "
                            + "exercised the numbering\n" + trace.describe()
            );
        }
    }

    /**
     * The instructions ask for nothing the document stopped supplying.
     *
     * <p>Checked against the exact bytes a provider receives — the prompt plus
     * the output-language tag, not the constant on its own — and against the
     * removed contract's own shapes rather than the English words it happened
     * to be written in. {@code "evidence"} is looked for quoted, because that
     * is the difference between a JSON property and a noun in a sentence.</p>
     */
    @Test
    void thePromptAsksForNoIdentityAndOffersNone() {
        String systemMessage = new DecisionPromptFactory()
                .create("en", "{\"events\":[]}")
                .systemMessage()
                .toLowerCase();

        for (String removed : List.of(
                "\"evidence\"",
                "\"evidence\":[",
                "evidenceids",
                "cite event",
                "cite events",
                "event id",
                "event ids",
                "id values"
        )) {
            assertFalse(
                    systemMessage.contains(removed),
                    "the provider-facing prompt still says " + removed
            );
        }
        assertTrue(
                systemMessage.contains(
                        "{\"decision\":\"comment\",\"comment\":\"...\"}"
                ),
                "the current comment shape is still stated"
        );
    }

    // ----------------------------------------------- what Kairon still holds

    /**
     * The numbering survives everywhere it is actually used.
     *
     * <p>The record numbers its events from one, the evidence maps each of those
     * numbers onto the observation it came from, and a change caused by one of
     * this request's events still points at it. None of the three is serialized;
     * all three are what makes the request correlatable at all.</p>
     */
    @Test
    void theInternalNumberingIsUnchangedByTheDocumentLosingIt() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        ProjectedObservation jump = fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"FSDJump",
                 "StarSystem":"Test A","SystemAddress":701,"JumpDist":12.5,
                 "FuelUsed":1.1,"BoostUsed":false}
                """);
        ProjectedObservation exit = fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:05Z","event":"SupercruiseExit",
                 "StarSystem":"Test A","SystemAddress":701,
                 "Body":"Test A 1","BodyID":1,"BodyType":"Planet"}
                """);

        LlmDecisionRequest request =
                factory.create(fixture.inputs(List.of(jump, exit)));

        assertEquals(
                List.of(1, 2),
                request.events().stream()
                        .map(LlmDecisionRequest.Event::id)
                        .toList(),
                "the record still numbers its events from one"
        );

        List<Integer> attributed = request.changes().stream()
                .map(LlmDecisionRequest.Change::eventId)
                .filter(java.util.Objects::nonNull)
                .toList();
        assertFalse(
                attributed.isEmpty(),
                "the fixture must produce at least one attributed change"
        );
        for (Integer eventId : attributed) {
            assertTrue(
                    eventId >= 1 && eventId <= request.events().size(),
                    () -> "a change points at no event of this request: "
                            + eventId
            );
        }

        String serialized = serializer.serialize(request);
        assertFalse(serialized.contains("\"id\""), serialized);
        assertFalse(serialized.contains("\"eventId\""), serialized);
        assertEquals(
                List.of(jump.busSequence(), exit.busSequence()),
                List.of(jump.busSequence(), exit.busSequence()),
                "the triggers stay in the order the events were projected in"
        );
    }

    /**
     * The trace says which observation each event came from, once.
     *
     * <p>It is the only written record of the numbering now, and it is the list
     * the turn was batched from rather than a second copy of it under another
     * name: the nth event was projected from the nth trigger. Every turn has it,
     * including one that never reached the provider.</p>
     */
    @Test
    void everyTracedTurnNamesItsOwnTriggersAndNothingCalledEvidence(
            @TempDir Path directory
    ) throws Exception {
        Path trace = directory.resolve("decision-pipeline-turns.jsonl");
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            replay(harness);
        }

        List<String> lines = Files.readAllLines(trace, StandardCharsets.UTF_8);
        assertFalse(lines.isEmpty(), "no turn was traced");
        for (String line : lines) {
            JsonNode traced = JSON.readTree(line);
            // The trace's own vocabulary, not its payloads: the system prompt
            // travels inside modelInput and legitimately uses "evidence" as an
            // ordinary English word about what a claim may rest on.
            for (String name : propertyNames(traced)) {
                assertFalse(
                        name.toLowerCase().contains("evidence"),
                        () -> "the trace still names a field " + name
                );
            }
            for (String name
                    : propertyNames(traced.path("validatedDecision"))) {
                assertFalse(
                        name.toLowerCase().contains("evidence")
                                || name.toLowerCase().contains("trigger"),
                        () -> "the traced response carries " + name
                                + ", which came from the request"
                );
            }
            List<Long> triggers = longs(traced.path("triggerBusSequences"));
            assertFalse(triggers.isEmpty(), line);
            long previous = 0L;
            for (Long triggerBusSequence : triggers) {
                assertTrue(triggerBusSequence > previous, line);
                previous = triggerBusSequence;
            }
            if (!traced.path("providerInvoked").booleanValue()) {
                continue;
            }
            assertEquals(
                    triggers.size(),
                    JSON.readTree(traced.path("situationTurn").textValue())
                            .path("events").size(),
                    () -> "the nth event is the nth trigger, so the two lists "
                            + "are the same length: " + line
            );
        }
    }

    // ------------------------------------------------------ response and size

    /** The validator neither expects nor tolerates an id-based citation. */
    @Test
    void theResponseContractHasNoCitationLeft() {
        ObserverResponseValidator validator = new ObserverResponseValidator();

        ValidatedObserverResponse accepted = validator.validate(
                "{\"decision\":\"COMMENT\",\"comment\":\"Landed cleanly.\"}",
                List.of()
        );
        assertEquals(
                ObserverResponseValidator.Status.VALID,
                accepted.status()
        );

        for (String citation : List.of("evidence", "evidenceIds", "eventIds")) {
            ValidatedObserverResponse refused = validator.validate(
                    "{\"decision\":\"COMMENT\",\"comment\":\"Landed.\",\""
                            + citation + "\":[1]}",
                    List.of()
            );
            assertEquals(
                    ObserverResponseValidator.Status.INVALID,
                    refused.status(),
                    citation
            );
            assertEquals(
                    List.of("INVALID_PROPERTIES"),
                    refused.violations(),
                    citation
            );
        }
    }

    /**
     * A delivered comment is attributed by the turn, not by the answer.
     *
     * <p>The coordinator computes the batch and hands it to the listener beside
     * the validated response. The two stay separate on purpose: one is what the
     * model said, the other is what it was asked about.</p>
     */
    @Test
    void aDeliveredCommentCarriesTheWholeBatchFromTheTurn(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.pipeline().respondWith(
                    "{\"decision\":\"COMMENT\",\"comment\":\"Arrived.\"}"
            );
            replay(harness);

            PipelineTrace trace = harness.trace();
            List<ObserverTurnListener.DecisionResolved> decisions =
                    harness.pipeline().decisions();
            assertEquals(trace.turns().size(), decisions.size());
            for (int index = 0; index < decisions.size(); index++) {
                assertEquals(
                        trace.turns().get(index).triggerBusSequences(),
                        decisions.get(index).triggerBusSequences(),
                        () -> "the listener is given the turn's own batch\n"
                                + trace.describe()
                );
            }
            assertFalse(
                    harness.pipeline().deliveredComments().isEmpty(),
                    "the fixture must actually deliver a comment"
            );
        }
    }

    /**
     * Dropping the identity does not spend the budget elsewhere.
     *
     * <p>Every request of the replay is measured against the production budget
     * the compactor enforces, and every one of them fits with room to spare —
     * the change removes characters and adds none, so a turn that fitted before
     * cannot stop fitting now.</p>
     */
    @Test
    void everyRequestStaysInsideTheConfiguredBudget(@TempDir Path directory) {
        int budget = DecisionTurnPolicy.production().maxSerializedCharacters();
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            replay(harness);

            PipelineTrace trace = harness.trace();
            assertTrue(trace.providerCalls() >= 2, trace.describe());
            for (PipelineTrace.TurnView turn : trace.turns()) {
                int size = turn.userMessage().length();
                assertTrue(
                        size <= budget,
                        () -> "a request of " + size + " characters exceeds "
                                + "the " + budget + " character budget\n"
                                + trace.describe()
                );
                assertFalse(
                        turn.contextIncomplete(),
                        () -> "nothing was dropped to make this turn fit\n"
                                + trace.describe()
                );
            }
        }
    }

    /** The serialized request is what the provider is handed, byte for byte. */
    @Test
    void theSerializedRequestIsTheWholeUserMessage(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            replay(harness);

            for (LlmClient.ModelInput input
                    : harness.pipeline().modelInputs()) {
                assertTrue(
                        input.userMessage().startsWith("{\"events\":[{\"event\""),
                        input.userMessage()
                );
            }
        }
    }

    // ------------------------------------------------------------- fixtures

    /**
     * A short replay that produces both a single-event and a batched turn.
     *
     * <p>The first batch is closed on its own so the jump is one turn; the two
     * that follow arrive together, which is the case a numbering would have
     * mattered for.</p>
     */
    private static void replay(SemanticPipelineHarness harness) {
        harness.journal("""
                        {"timestamp":"2026-07-30T09:59:00Z","event":"LoadGame",
                         "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                         "ShipName":"Wanderer"}
                        """)
                .journal("""
                        {"timestamp":"2026-07-30T10:00:00Z","event":"FSDJump",
                         "StarSystem":"Test A","SystemAddress":701,
                         "JumpDist":12.5,"FuelUsed":1.1,"BoostUsed":false}
                        """)
                .closeBatch();
        harness.journal("""
                        {"timestamp":"2026-07-30T10:00:05Z",
                         "event":"SupercruiseExit","StarSystem":"Test A",
                         "SystemAddress":701,"Body":"Test A 1","BodyID":1,
                         "BodyType":"Planet"}
                        """)
                .journal("""
                        {"timestamp":"2026-07-30T10:00:09Z",
                         "event":"Touchdown","PlayerControlled":true,
                         "StarSystem":"Test A","Body":"Test A 1",
                         "Latitude":18.7,"Longitude":-35.0}
                        """)
                .closeBatch();
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        array.forEach(value -> values.add(value.longValue()));
        return List.copyOf(values);
    }

    private static List<String> propertyNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }
}
