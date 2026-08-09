package kairon.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;


/**
 * Strict local SILENT/COMMENT response validation.
 *
 * <p>A response says what it decided and, for a comment, what to say. It cites
 * nothing: the request carries no event ids, so a citation could only name a
 * number the model was never shown, and no reader downstream ever branched on
 * which subset of a batch came back.</p>
 *
 * <p>The validator therefore needs the response and the repetition memory, and
 * nothing about the request. What it produces is a description of the answer
 * and only of the answer. Attribution — which observations a comment was
 * produced from — is a fact about the batch that the coordinator already holds,
 * and it is attached there rather than smuggled through a response record that
 * would then be part model and part Kairon.</p>
 *
 * <h2>How long a comment may be: no longer bounded</h2>
 * <p>There was a ceiling of four sentences, and it is gone as of 2026-08-08.
 * It had already been raised once — from two, after fifteen of seventy-five
 * turns of the 2026-08-06 replay were refused on sentence count alone and every
 * one of them was a comment Kairon would have spoken — and the same cost
 * arrived again at four. On the first live turn ever to carry a sample's
 * payout, the answer named the figure and the first footfall, ran to five
 * sentences, and was refused whole. A batch is consumed once and there is no
 * second decision, so a refusal is not a shorter comment: it is silence.</p>
 *
 * <p><strong>Nothing bounds a comment's length now.</strong> That is the
 * accepted trade and it is not free — the comment is spoken, so an answer that
 * runs long costs synthesis time and playback the Commander waits through, and
 * the coordinator holds the turn until playback completes. Brevity is asked for
 * by the role and by nothing else. If answers start becoming paragraphs, this
 * is the decision to revisit, and the shape to reach for is a character bound
 * on what gets spoken rather than a sentence count on what may be said.</p>
 */
public final class ObserverResponseValidator {

    private static final Set<String> SILENT_PROPERTIES = Set.of("decision");
    private static final Set<String> COMMENT_PROPERTIES = Set.of(
            "decision",
            "comment"
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
     * Validates one raw model response.
     *
     * @param previousComments delivered comment texts, for repetition only
     */
    public ValidatedObserverResponse validate(
            String rawOutput,
            List<String> previousComments
    ) {
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
                    null
            );
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
                detail
        );
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
     * One validated response, and nothing but the response.
     *
     * <p>Every component here was read out of what the model returned, or is
     * Kairon's verdict on it. Nothing is derived from the request or the batch.
     * That separation is the point: a record that mixed the answer with facts
     * about the question invites a reader to treat the second as though the
     * model had asserted it, which is how a comment ends up "citing" events
     * nobody ever showed it.</p>
     */
    public record ValidatedObserverResponse(
            Status status,
            Decision decision,
            String comment,
            List<String> violations,
            String failure
    ) {

        public ValidatedObserverResponse {
            status = Objects.requireNonNull(status, "status");
            violations = List.copyOf(Objects.requireNonNull(
                    violations,
                    "violations"
            ));
            switch (status) {
                case VALID -> requireValidDecision(
                        decision,
                        comment,
                        violations,
                        failure
                );
                case INVALID -> {
                    if (decision != null
                            || comment != null
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
                if (comment != null) {
                    throw new IllegalArgumentException(
                            "valid SILENT response is inconsistent"
                    );
                }
                return;
            }
            if (comment == null || comment.isBlank()) {
                throw new IllegalArgumentException(
                        "valid COMMENT response is inconsistent"
                );
            }
        }
    }
}
