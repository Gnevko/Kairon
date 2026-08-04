package kairon.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.turn.evidence.DecisionEvidence;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;

/**
 * Strict local SILENT/COMMENT response validation against the turn that was
 * actually sent to the provider.
 *
 * <p>A response cites local event ids, which mean nothing outside the request
 * they came from. Validation therefore does two jobs that must not be split: it
 * refuses an id the request never offered, and it translates the surviving ids
 * back to the bus sequences everything downstream is keyed on. Nothing outside
 * this class ever sees an unmapped local id.</p>
 */
public final class ObserverResponseValidator {

    private static final Set<String> SILENT_PROPERTIES = Set.of("decision");
    private static final Set<String> COMMENT_PROPERTIES = Set.of(
            "decision",
            "comment",
            "evidence"
    );
    private static final Pattern SENTENCE_TERMINATOR =
            Pattern.compile(
                    "[.!?\\u2026]+(?=\\s|$)",
                    UNICODE_CHARACTER_CLASS
            );

    private final ObjectMapper responseMapper;
    private final CommentNoveltyGuard noveltyGuard;

    public ObserverResponseValidator() {
        responseMapper = new ObjectMapper(
                JsonFactory.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .build()
        ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        noveltyGuard = new CommentNoveltyGuard();
    }

    /**
     * Validates one raw model response against the turn's evidence.
     *
     * @param evidence         the local ids this request offered, and what they
     *                         stand for
     * @param previousComments delivered comment texts, for repetition only
     */
    public ValidatedObserverResponse validate(
            String rawOutput,
            DecisionEvidence evidence,
            List<String> previousComments
    ) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(previousComments, "previousComments");

        List<String> violations = new ArrayList<>();
        JsonNode root;
        try {
            root = responseMapper.readTree(Objects.requireNonNull(
                    rawOutput,
                    "rawOutput"
            ));
        } catch (RuntimeException | IOException exception) {
            return invalid("MALFORMED_JSON");
        }
        if (root == null || !root.isObject()) {
            return invalid("RESPONSE_NOT_OBJECT");
        }

        JsonNode decisionNode = root.get("decision");
        if (decisionNode == null || !decisionNode.isTextual()) {
            return invalid("DECISION_MISSING_OR_NOT_STRING");
        }
        Decision decision;
        try {
            decision = Decision.valueOf(decisionNode.textValue());
        } catch (IllegalArgumentException exception) {
            return invalid("UNKNOWN_DECISION");
        }

        Set<String> actualProperties = new HashSet<>();
        root.fieldNames().forEachRemaining(actualProperties::add);
        Set<String> expected = decision == Decision.SILENT
                ? SILENT_PROPERTIES
                : COMMENT_PROPERTIES;
        if (!actualProperties.equals(expected)) {
            violations.add("INVALID_PROPERTIES");
        }

        if (decision == Decision.SILENT) {
            if (!violations.isEmpty()) {
                return invalid(violations);
            }
            return new ValidatedObserverResponse(
                    Status.VALID,
                    Decision.SILENT,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    null
            );
        }

        List<Integer> cited = parseEvidence(root.get("evidence"), violations);
        validateEvidenceOrder(cited, violations);
        if (cited.size() > evidence.size()) {
            violations.add("EVIDENCE_EXCEEDS_EVENT_COUNT");
        }
        if (cited.stream().anyMatch(id -> !evidence.contains(id))) {
            violations.add("UNKNOWN_EVIDENCE_EVENT_ID");
        }
        if (cited.isEmpty()) {
            violations.add("COMMENT_EVIDENCE_EMPTY");
        }

        JsonNode commentNode = root.get("comment");
        String comment = commentNode != null && commentNode.isTextual()
                ? commentNode.textValue()
                : null;
        if (comment == null) {
            violations.add("COMMENT_MISSING_OR_NOT_STRING");
        } else if (comment.isBlank()) {
            violations.add("COMMENT_BLANK");
        } else {
            int sentenceCount = countSentences(comment);
            if (sentenceCount < 1 || sentenceCount > 2) {
                violations.add("COMMENT_SENTENCE_COUNT");
            }
            noveltyGuard.findViolation(comment, previousComments)
                    .ifPresent(violations::add);
        }

        if (!violations.isEmpty()) {
            return invalid(violations);
        }
        return new ValidatedObserverResponse(
                Status.VALID,
                Decision.COMMENT,
                comment,
                cited,
                evidence.resolve(cited),
                List.of(),
                null
        );
    }

    public ValidatedObserverResponse modelCallFailed(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return new ValidatedObserverResponse(
                Status.MODEL_CALL_FAILED,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                failure.getClass().getSimpleName()
        );
    }

    /**
     * The turn's mandatory content did not fit the configured budget.
     *
     * <p>No provider call happened, so there is no response to validate. The
     * outcome travels on the same channel as a failed model call because it is
     * the same kind of fact: the turn produced no decision. It is never a
     * decision, never a comment, and never a synthesised {@code SILENT}.</p>
     */
    public static ValidatedObserverResponse contextTooLarge(String detail) {
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        return new ValidatedObserverResponse(
                Status.CONTEXT_TOO_LARGE,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                detail
        );
    }

    private static List<Integer> parseEvidence(
            JsonNode evidenceNode,
            List<String> violations
    ) {
        if (evidenceNode == null || !evidenceNode.isArray()) {
            violations.add("EVIDENCE_MISSING_OR_NOT_ARRAY");
            return List.of();
        }
        List<Integer> cited = new ArrayList<>(evidenceNode.size());
        for (JsonNode item : evidenceNode) {
            if (!item.isIntegralNumber() || !item.canConvertToInt()) {
                violations.add("EVIDENCE_EVENT_ID_NOT_INTEGER");
                continue;
            }
            int localId = item.intValue();
            if (localId < 1) {
                violations.add("EVIDENCE_EVENT_ID_NOT_POSITIVE");
            } else {
                cited.add(localId);
            }
        }
        return List.copyOf(cited);
    }

    private static void validateEvidenceOrder(
            List<Integer> cited,
            List<String> violations
    ) {
        Set<Integer> seen = new HashSet<>();
        Integer previous = null;
        for (Integer localId : cited) {
            boolean duplicate = !seen.add(localId);
            if (duplicate && !violations.contains(
                    "DUPLICATE_EVIDENCE_EVENT_ID"
            )) {
                violations.add("DUPLICATE_EVIDENCE_EVENT_ID");
            }
            boolean notAscending = previous != null && localId <= previous;
            if (notAscending && !violations.contains(
                    "EVIDENCE_EVENT_IDS_NOT_ASCENDING"
            )) {
                violations.add("EVIDENCE_EVENT_IDS_NOT_ASCENDING");
            }
            previous = localId;
        }
    }

    private static int countSentences(String text) {
        Matcher matcher = SENTENCE_TERMINATOR.matcher(text);
        int count = 0;
        int lastEnd = 0;
        while (matcher.find()) {
            count++;
            lastEnd = matcher.end();
        }
        if (!text.substring(lastEnd).strip().isEmpty()) {
            count++;
        }
        return count;
    }

    private static ValidatedObserverResponse invalid(String violation) {
        return invalid(List.of(violation));
    }

    private static ValidatedObserverResponse invalid(
            List<String> violations
    ) {
        return new ValidatedObserverResponse(
                Status.INVALID,
                null,
                null,
                List.of(),
                List.of(),
                List.copyOf(violations),
                null
        );
    }

    public enum Decision {
        SILENT,
        COMMENT
    }

    public enum Status {
        VALID,
        INVALID,
        MODEL_CALL_FAILED,

        /**
         * The turn's mandatory content exceeded the character budget, so the
         * provider was never called. Fail closed: no comment, no synthesised
         * silence, no retry at a different budget.
         */
        CONTEXT_TOO_LARGE
    }

    /**
     * One validated response, in both vocabularies.
     *
     * <p>{@code evidence} is what the model said; {@code
     * evidenceTriggerBusSequences} is what it means. Both are recorded so the
     * trace can show the mapping rather than assert it.</p>
     */
    public record ValidatedObserverResponse(
            Status status,
            Decision decision,
            String comment,
            List<Integer> evidence,
            List<Long> evidenceTriggerBusSequences,
            List<String> violations,
            String failure
    ) {

        public ValidatedObserverResponse {
            status = Objects.requireNonNull(status, "status");
            evidence = List.copyOf(
                    Objects.requireNonNull(evidence, "evidence")
            );
            evidenceTriggerBusSequences = List.copyOf(
                    Objects.requireNonNull(
                            evidenceTriggerBusSequences,
                            "evidenceTriggerBusSequences"
                    )
            );
            violations = List.copyOf(Objects.requireNonNull(
                    violations,
                    "violations"
            ));
            if (evidence.size() != evidenceTriggerBusSequences.size()) {
                throw new IllegalArgumentException(
                        "every cited event id must resolve to one bus sequence"
                );
            }
            switch (status) {
                case VALID -> requireValidDecision(
                        decision,
                        comment,
                        evidence,
                        violations,
                        failure
                );
                case INVALID -> {
                    if (decision != null
                            || comment != null
                            || !evidence.isEmpty()
                            || violations.isEmpty()
                            || failure != null) {
                        throw new IllegalArgumentException(
                                "invalid response metadata is inconsistent"
                        );
                    }
                }
                case MODEL_CALL_FAILED, CONTEXT_TOO_LARGE -> {
                    if (decision != null
                            || comment != null
                            || !evidence.isEmpty()
                            || !violations.isEmpty()
                            || failure == null
                            || failure.isBlank()) {
                        throw new IllegalArgumentException(
                                status + " metadata is inconsistent"
                        );
                    }
                }
            }
        }

        @JsonIgnore
        public boolean isDeliverableComment() {
            return status == Status.VALID
                    && decision == Decision.COMMENT;
        }

        private static void requireValidDecision(
                Decision decision,
                String comment,
                List<Integer> evidence,
                List<String> violations,
                String failure
        ) {
            if (decision == null || !violations.isEmpty()
                    || failure != null) {
                throw new IllegalArgumentException(
                        "valid response metadata is inconsistent"
                );
            }
            if (decision == Decision.SILENT) {
                if (comment != null || !evidence.isEmpty()) {
                    throw new IllegalArgumentException(
                            "valid SILENT response is inconsistent"
                    );
                }
                return;
            }
            if (comment == null || comment.isBlank() || evidence.isEmpty()) {
                throw new IllegalArgumentException(
                        "valid COMMENT response is inconsistent"
                );
            }
            int previous = 0;
            for (Integer localId : evidence) {
                if (localId == null || localId <= previous) {
                    throw new IllegalArgumentException(
                            "valid COMMENT evidence must be positive, "
                                    + "unique, and ascending"
                    );
                }
                previous = localId;
            }
        }
    }
}
