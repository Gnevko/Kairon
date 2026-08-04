package kairon.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.llm.ObserverResponseValidator.Decision;
import kairon.llm.ObserverResponseValidator.Status;
import kairon.llm.ObserverResponseValidator.ValidatedObserverResponse;
import kairon.turn.evidence.DecisionEvidence;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ObserverResponseValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LEGACY_EVIDENCE_PROPERTY =
            "evidenceTrigger" + "BusSequences";

    private static final List<String> PREVIOUS_COMMENTS =
            List.of("A previously delivered observation.");

    private final ObserverResponseValidator validator =
            new ObserverResponseValidator();

    @Test
    void acceptsSilenceAsOneProperty() {
        ValidatedObserverResponse response = validator.validate(
                "{\"decision\":\"SILENT\"}",
                evidence(101L),
                PREVIOUS_COMMENTS
        );

        assertEquals(Status.VALID, response.status());
        assertEquals(Decision.SILENT, response.decision());
        assertNull(response.comment());
        assertEquals(List.of(), response.evidence());
        assertEquals(List.of(), response.evidenceTriggerBusSequences());
    }

    @Test
    void rejectsSilenceCarryingACommentOrEvidence() {
        assertViolation(
                "{\"decision\":\"SILENT\",\"comment\":null}",
                evidence(101L),
                "INVALID_PROPERTIES"
        );
        assertViolation(
                "{\"decision\":\"SILENT\",\"evidence\":[1]}",
                evidence(101L),
                "INVALID_PROPERTIES"
        );
    }

    /** The whole point of local ids: they are translated, not trusted. */
    @Test
    void mapsLocalEvidenceBackToTheObservationsItStandsFor() {
        ValidatedObserverResponse response = validator.validate(
                comment("Supercruise is engaged.", 1),
                evidence(101L),
                PREVIOUS_COMMENTS
        );

        assertEquals(Status.VALID, response.status());
        assertEquals(Decision.COMMENT, response.decision());
        assertEquals("Supercruise is engaged.", response.comment());
        assertEquals(List.of(1), response.evidence());
        assertEquals(
                List.of(101L),
                response.evidenceTriggerBusSequences()
        );
    }

    @Test
    void mapsEachLocalIdToItsOwnPositionInTheRequest() {
        ValidatedObserverResponse response = validator.validate(
                comment("The route ended in the new system.", 1, 3),
                evidence(101L, 102L, 103L),
                PREVIOUS_COMMENTS
        );

        assertEquals(Status.VALID, response.status());
        assertEquals(List.of(1, 3), response.evidence());
        assertEquals(
                List.of(101L, 103L),
                response.evidenceTriggerBusSequences()
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> response.evidenceTriggerBusSequences().add(104L)
        );
    }

    /**
     * A local id means nothing outside its own request.
     *
     * <p>An id past the end of the events array, an internal bus sequence
     * echoed back, and an id from a previous turn are all the same failure: the
     * request never offered it.</p>
     */
    @Test
    void rejectsAnyIdTheRequestDidNotOffer() {
        DecisionEvidence current = evidence(101L, 103L);

        for (int outside : new int[]{3, 4, 101, 999_999}) {
            assertViolation(
                    comment("Not citable.", outside),
                    current,
                    "UNKNOWN_EVIDENCE_EVENT_ID"
            );
        }
        assertFalse(current.contains(3));
        assertTrue(current.contains(2));
        assertEquals(2, current.size());
    }

    @Test
    void rejectsDuplicateEvidence() {
        assertViolation(
                comment("Duplicate evidence is invalid.", 1, 2, 1),
                evidence(101L, 103L),
                "DUPLICATE_EVIDENCE_EVENT_ID"
        );
    }

    @Test
    void rejectsNonAscendingEvidenceWithoutSortingIt() {
        ValidatedObserverResponse response = validator.validate(
                comment("Ordering is part of the contract.", 2, 1),
                evidence(101L, 103L),
                PREVIOUS_COMMENTS
        );

        assertEquals(Status.INVALID, response.status());
        assertTrue(response.violations().contains(
                "EVIDENCE_EVENT_IDS_NOT_ASCENDING"
        ));
        assertEquals(List.of(), response.evidence());
    }

    @Test
    void rejectsStringNullDecimalAndScientificEvidenceValues() {
        for (String cited : List.of(
                "\"1\"",
                "null",
                "1.5",
                "1e2",
                "[1]"
        )) {
            assertViolation(
                    "{\"decision\":\"COMMENT\","
                            + "\"comment\":\"Numeric integers only.\","
                            + "\"evidence\":["
                            + cited
                            + "]}",
                    evidence(101L),
                    "EVIDENCE_EVENT_ID_NOT_INTEGER"
            );
        }
    }

    @Test
    void rejectsZeroNegativeAndOutOfRangeIntegerEvidence() {
        for (String cited : List.of("0", "-1", "9223372036854775808")) {
            ValidatedObserverResponse response = validator.validate(
                    "{\"decision\":\"COMMENT\","
                            + "\"comment\":\"Positive local ids only.\","
                            + "\"evidence\":["
                            + cited
                            + "]}",
                    evidence(101L),
                    PREVIOUS_COMMENTS
            );

            assertEquals(Status.INVALID, response.status());
            assertTrue(response.violations().stream().anyMatch(
                    violation -> violation.startsWith("EVIDENCE_EVENT_ID_NOT_")
            ));
        }
    }

    /** The previous contract's property name is not accepted as a synonym. */
    @Test
    void rejectsRemovedEvidencePropertyWithoutCompatibility() {
        ValidatedObserverResponse response = validator.validate(
                "{\"decision\":\"COMMENT\","
                        + "\"comment\":\"Removed contracts are invalid.\",\""
                        + LEGACY_EVIDENCE_PROPERTY
                        + "\":[101]}",
                evidence(101L),
                PREVIOUS_COMMENTS
        );

        assertEquals(Status.INVALID, response.status());
        assertTrue(response.violations().contains("INVALID_PROPERTIES"));
        assertTrue(response.violations().contains(
                "EVIDENCE_MISSING_OR_NOT_ARRAY"
        ));
    }

    @Test
    void requiresCommentAndEvidenceTogether() {
        assertViolation(
                "{\"decision\":\"COMMENT\",\"comment\":\"Evidence required.\"}",
                evidence(101L),
                "EVIDENCE_MISSING_OR_NOT_ARRAY"
        );
        assertViolation(
                "{\"decision\":\"COMMENT\",\"evidence\":[1]}",
                evidence(101L),
                "COMMENT_MISSING_OR_NOT_STRING"
        );
    }

    @Test
    void rejectsExtraPropertiesAndDuplicateJsonProperties() {
        assertViolation(
                "{\"decision\":\"SILENT\",\"extra\":1}",
                evidence(101L),
                "INVALID_PROPERTIES"
        );
        ValidatedObserverResponse duplicateProperty = validator.validate(
                "{\"decision\":\"SILENT\",\"decision\":\"COMMENT\"}",
                evidence(101L),
                PREVIOUS_COMMENTS
        );
        assertEquals(Status.INVALID, duplicateProperty.status());
        assertTrue(duplicateProperty.violations().contains("MALFORMED_JSON"));
    }

    /** The repetition guard runs locally and is unaffected by the id change. */
    @Test
    void rejectsARepeatOfADeliveredComment() {
        assertViolation(
                comment("A previously delivered observation.", 1),
                evidence(101L),
                "DUPLICATE_PREVIOUS_COMMENT"
        );
    }

    @Test
    void writesActualContractExamplesFromValidatorInputsAndOutputs()
            throws Exception {
        DecisionEvidence single = evidence(101L);
        DecisionEvidence multiple = evidence(101L, 102L, 103L);
        List<Example> examples = List.of(
                new Example(
                        "valid-silent",
                        "{\"decision\":\"SILENT\"}",
                        single
                ),
                new Example(
                        "valid-single-comment",
                        comment("Supercruise is engaged.", 1),
                        single
                ),
                new Example(
                        "valid-multi-comment",
                        comment("The route ended in the new system.", 1, 3),
                        multiple
                ),
                new Example(
                        "rejected-removed-contract",
                        "{\"decision\":\"COMMENT\","
                                + "\"comment\":\"Old evidence field.\",\""
                                + LEGACY_EVIDENCE_PROPERTY
                                + "\":[101]}",
                        single
                ),
                new Example(
                        "rejected-unknown-event-id",
                        comment("Unknown event.", 9),
                        single
                ),
                new Example(
                        "rejected-bus-sequence-echoed-back",
                        comment("Wrong identity domain.", 101),
                        single
                )
        );

        Path output = Path.of(
                "target",
                "observer-response-contract-examples.jsonl"
        );
        Files.createDirectories(output.getParent());
        List<String> lines = new ArrayList<>();
        for (Example example : examples) {
            ValidatedObserverResponse response = validator.validate(
                    example.rawResponse(),
                    example.evidence(),
                    PREVIOUS_COMMENTS
            );
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("scenario", example.scenario());
            line.put("rawResponse", JSON.readTree(example.rawResponse()));
            line.put("validatedResponse", response);
            lines.add(JSON.writeValueAsString(line));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);

        assertEquals(examples.size(), Files.readAllLines(output).size());
        for (String line : Files.readAllLines(output)) {
            JsonNode parsed = JSON.readTree(line);
            assertTrue(parsed.path("rawResponse").isObject());
            assertTrue(parsed.path("validatedResponse").isObject());
        }
    }

    private void assertViolation(
            String rawResponse,
            DecisionEvidence evidence,
            String expectedViolation
    ) {
        ValidatedObserverResponse response = validator.validate(
                rawResponse,
                evidence,
                PREVIOUS_COMMENTS
        );
        assertEquals(Status.INVALID, response.status());
        assertTrue(
                response.violations().contains(expectedViolation),
                () -> "Expected " + expectedViolation + " in "
                        + response.violations()
        );
        assertFalse(response.isDeliverableComment());
    }

    private static String comment(String text, int... eventIds) {
        StringBuilder response = new StringBuilder(
                "{\"decision\":\"COMMENT\",\"comment\":"
        );
        response.append('"').append(text).append('"');
        response.append(",\"evidence\":[");
        for (int index = 0; index < eventIds.length; index++) {
            if (index > 0) {
                response.append(',');
            }
            response.append(eventIds[index]);
        }
        return response.append("]}").toString();
    }

    /**
     * The only thing a response may cite: this turn's events, by position.
     *
     * <p>Deliberately tiny. The validator never sees the request document, so a
     * hidden state-change source, a context fact or a previous comment cannot
     * become evidence by accident — none of them has an id at all.</p>
     */
    private static DecisionEvidence evidence(long... triggerBusSequences) {
        List<Long> allowed = new ArrayList<>(triggerBusSequences.length);
        for (long triggerBusSequence : triggerBusSequences) {
            allowed.add(triggerBusSequence);
        }
        return new DecisionEvidence(allowed);
    }

    private record Example(
            String scenario,
            String rawResponse,
            DecisionEvidence evidence
    ) {
    }
}
