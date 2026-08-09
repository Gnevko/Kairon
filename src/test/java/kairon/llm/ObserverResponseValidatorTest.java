package kairon.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.llm.ObserverResponseValidator.Decision;
import kairon.llm.ObserverResponseValidator.Status;
import kairon.llm.ObserverResponseValidator.ValidatedObserverResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ObserverResponseValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Contracts the response used to have, spelled so no source search for
     * them lands here by accident.
     */
    private static final String REMOVED_CITATION_PROPERTY = "evi" + "dence";
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
                PREVIOUS_COMMENTS
        );

        assertEquals(Status.VALID, response.status());
        assertEquals(Decision.SILENT, response.decision());
        assertNull(response.comment());
        assertEquals(List.of(), response.violations());
    }

    @Test
    void rejectsSilenceCarryingACommentOrACitation() {
        assertViolation(
                "{\"decision\":\"SILENT\",\"comment\":null}",
                "INVALID_PROPERTIES"
        );
        assertViolation(
                "{\"decision\":\"SILENT\",\""
                        + REMOVED_CITATION_PROPERTY
                        + "\":[1]}",
                "INVALID_PROPERTIES"
        );
    }

    /**
     * The validator accepts exactly what the prompt asks for.
     *
     * <p>Read out of {@code SYSTEM_PROMPT} rather than restated here. The two
     * halves of this contract are written in different files and enforced by
     * different code, so a copy of the shapes in the test would prove only that
     * the test agrees with itself — a prompt that drifted would still pass while
     * every real response it produced was refused.</p>
     */
    @Test
    void everyResponseShapeThePromptOffersIsOneTheValidatorAccepts() {
        List<String> offered = responseShapesIn(
                DecisionPromptFactory.SYSTEM_PROMPT
        );

        assertEquals(
                List.of(
                        "{\"decision\":\"SILENT\"}",
                        "{\"decision\":\"COMMENT\",\"comment\":\"...\"}"
                ),
                offered,
                "the prompt offers a silence and a comment, and nothing else"
        );
        for (String shape : offered) {
            String usable = shape.replace(
                    "\"...\"",
                    "\"Supercruise is engaged.\""
            );
            ValidatedObserverResponse response =
                    validator.validate(usable, PREVIOUS_COMMENTS);
            assertEquals(
                    Status.VALID,
                    response.status(),
                    () -> "the prompt offers a shape the validator refuses: "
                            + usable + " -> " + response.violations()
            );
        }
    }

    /** Every JSON object in the prompt that answers with a decision. */
    private static List<String> responseShapesIn(String prompt) {
        List<String> shapes = new ArrayList<>();
        int from = prompt.indexOf("{\"decision\":");
        while (from >= 0) {
            int end = prompt.indexOf('}', from);
            shapes.add(prompt.substring(from, end + 1));
            from = prompt.indexOf("{\"decision\":", end);
        }
        return List.copyOf(shapes);
    }

    /** A decision and a sentence. The contract has no third property. */
    @Test
    void acceptsACommentThatCitesNothing() {
        ValidatedObserverResponse response = validator.validate(
                comment("Supercruise is engaged."),
                PREVIOUS_COMMENTS
        );

        assertEquals(Status.VALID, response.status());
        assertEquals(Decision.COMMENT, response.decision());
        assertEquals("Supercruise is engaged.", response.comment());
    }

    /**
     * The record describes the answer, and only the answer.
     *
     * <p>Asserted structurally rather than by naming today's fields: the defect
     * this guards against is a request-derived value reappearing on the record
     * under a fresh name, and a list of forbidden names cannot see that. Every
     * component here must be something the response said, or Kairon's verdict on
     * it.</p>
     */
    @Test
    void carriesNothingDerivedFromTheRequestOrTheBatch() {
        List<String> components = new ArrayList<>();
        for (RecordComponent component
                : ValidatedObserverResponse.class.getRecordComponents()) {
            components.add(component.getName());
        }

        assertEquals(
                List.of("status", "decision", "comment", "violations",
                        "failure"),
                components,
                "a validated response is the parsed answer and the verdict"
        );
        for (String component : components) {
            assertFalse(
                    component.toLowerCase().contains("evidence")
                            || component.toLowerCase().contains("bussequence")
                            || component.toLowerCase().contains("trigger")
                            || component.toLowerCase().contains("event"),
                    "the response record names something from the request: "
                            + component
            );
        }
    }

    /** The validator is given the answer and the repetition memory, no more. */
    @Test
    void needsNothingAboutTheRequestToValidateAnAnswer() {
        assertEquals(
                2,
                java.util.Arrays.stream(
                        ObserverResponseValidator.class.getMethods())
                        .filter(method -> method.getName().equals("validate"))
                        .findFirst()
                        .orElseThrow()
                        .getParameterCount(),
                "validate takes the raw output and the previous comments"
        );
    }

    /**
     * The citation contract is gone, and no compatibility survives it.
     *
     * <p>Every shape a model trained on the old prompt could send back — the
     * removed property with a valid local id, with an unknown one, with an
     * internal bus sequence echoed into it, and the earlier property name — is
     * one extra property and therefore one invalid response. There is nothing
     * for Kairon to check the numbers against, so it does not try.</p>
     */
    @Test
    void rejectsEveryFormOfTheRemovedCitationWithoutInterpretingIt() {
        for (String cited : List.of("[1]", "[9]", "[101]", "[]", "[1,2]")) {
            ValidatedObserverResponse response = validator.validate(
                    "{\"decision\":\"COMMENT\","
                            + "\"comment\":\"Removed contracts are invalid.\",\""
                            + REMOVED_CITATION_PROPERTY
                            + "\":" + cited + "}",
                    PREVIOUS_COMMENTS
            );

            assertEquals(Status.INVALID, response.status(), cited);
            assertEquals(
                    List.of("INVALID_PROPERTIES"),
                    response.violations(),
                    () -> "a removed property is one violation, not a family "
                            + "of id checks: " + cited
            );
        }
        assertViolation(
                "{\"decision\":\"COMMENT\","
                        + "\"comment\":\"Removed contracts are invalid.\",\""
                        + LEGACY_EVIDENCE_PROPERTY
                        + "\":[101]}",
                "INVALID_PROPERTIES"
        );
    }

    @Test
    void requiresTheCommentText() {
        assertViolation(
                "{\"decision\":\"COMMENT\"}",
                "COMMENT_MISSING_OR_NOT_STRING"
        );
        assertViolation(
                "{\"decision\":\"COMMENT\",\"comment\":\"   \"}",
                "COMMENT_BLANK"
        );
    }

    @Test
    void rejectsExtraPropertiesAndDuplicateJsonProperties() {
        assertViolation(
                "{\"decision\":\"SILENT\",\"extra\":1}",
                "INVALID_PROPERTIES"
        );
        ValidatedObserverResponse duplicateProperty = validator.validate(
                "{\"decision\":\"SILENT\",\"decision\":\"COMMENT\"}",
                PREVIOUS_COMMENTS
        );
        assertEquals(Status.INVALID, duplicateProperty.status());
        assertTrue(duplicateProperty.violations().contains("MALFORMED_JSON"));
    }

    /** The repetition guard runs locally and is unaffected by the change. */
    @Test
    void rejectsARepeatOfADeliveredComment() {
        assertViolation(
                comment("A previously delivered observation."),
                "DUPLICATE_PREVIOUS_COMMENT"
        );
    }

    /**
     * Length is not a reason to refuse an answer.
     *
     * <p>The ceiling was two, then four, and is now gone (2026-08-08). Both
     * raisings were bought the same way: fifteen of the seventy-five turns of
     * the 2026-06 replay refused on sentence count alone, and then the first
     * live turn ever to carry a sample's payout — which named the figure and the
     * first footfall, ran to five sentences, and was lost whole. A batch is
     * consumed once, so a refusal is silence rather than a shorter comment.</p>
     *
     * <p>What is asserted here is the absence: a five-sentence answer and a
     * paragraph are both valid, and nothing in the validator measures length.
     * Brevity is the role's to ask for.</p>
     */
    @Test
    void acceptsAnAnswerOfAnyLength() {
        for (String text : List.of(
                "Supercruise is engaged.",
                "Supercruise is engaged. The star is close. Heat is rising. "
                        + "Nothing alarming yet.",
                "One. Two. Three. Four. Five.",
                "One. Two. Three. Four. Five. Six. Seven. Eight."
        )) {
            ValidatedObserverResponse response = validator.validate(
                    comment(text),
                    PREVIOUS_COMMENTS
            );

            assertEquals(Status.VALID, response.status(), text);
            assertEquals(List.of(), response.violations(), text);
        }
    }

    /** No violation names an id, because no id was ever offered. */
    @Test
    void noViolationCodeRefersToAnEventId() {
        for (String raw : List.of(
                "{\"decision\":\"SILENT\",\"extra\":1}",
                "{\"decision\":\"COMMENT\"}",
                "{\"decision\":\"COMMENT\",\"comment\":\"x\",\""
                        + REMOVED_CITATION_PROPERTY
                        + "\":[1]}"
        )) {
            for (String violation : validator.validate(
                    raw,
                    PREVIOUS_COMMENTS
            ).violations()) {
                assertFalse(
                        violation.contains("EVENT_ID")
                                || violation.contains("EVIDENCE"),
                        () -> "the response contract still speaks of ids: "
                                + violation
                );
            }
        }
    }

    @Test
    void writesActualContractExamplesFromValidatorInputsAndOutputs()
            throws Exception {
        List<Example> examples = List.of(
                new Example("valid-silent", "{\"decision\":\"SILENT\"}"),
                new Example(
                        "valid-comment",
                        comment("Supercruise is engaged.")
                ),
                new Example(
                        "rejected-removed-citation",
                        "{\"decision\":\"COMMENT\","
                                + "\"comment\":\"Old citation field.\",\""
                                + REMOVED_CITATION_PROPERTY
                                + "\":[1]}"
                ),
                new Example(
                        "rejected-removed-contract",
                        "{\"decision\":\"COMMENT\","
                                + "\"comment\":\"Old evidence field.\",\""
                                + LEGACY_EVIDENCE_PROPERTY
                                + "\":[101]}"
                ),
                new Example(
                        "rejected-missing-comment",
                        "{\"decision\":\"COMMENT\"}"
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
            JsonNode validated = parsed.path("validatedResponse");
            assertTrue(validated.isObject());
            assertFalse(
                    validated.toString().toLowerCase().contains("evidence"),
                    () -> "the validated response still records evidence: "
                            + line
            );
        }
    }

    private void assertViolation(
            String rawResponse,
            String expectedViolation
    ) {
        ValidatedObserverResponse response = validator.validate(
                rawResponse,
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

    private static String comment(String text) {
        return "{\"decision\":\"COMMENT\",\"comment\":\"" + text + "\"}";
    }

    private record Example(String scenario, String rawResponse) {
    }
}
