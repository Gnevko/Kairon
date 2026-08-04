package kairon.observer.decision;

import kairon.turn.evidence.DecisionEvidence;
import kairon.turn.overflow.ContextOverflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fits a decision request to the character budget, or refuses.
 *
 * <p>One rung: the selected context can go, because it is by construction the
 * part the events could be understood without. Events, changes and the
 * trajectory are mandatory — an event is the evidence a comment must rest on,
 * an exact state change is never compactable, and the trajectory is six items
 * at most — so a request that cannot hold them is not compacted into a smaller
 * lie. It fails closed, the provider is never called, and the turn ends as
 * {@code CONTEXT_TOO_LARGE}.</p>
 *
 * <p>Dropping the context sets {@code contextIncomplete}, which is the only
 * thing the model is told about it: something relevant was left out, so do not
 * treat absence here as proof of absence in the world.</p>
 */
public final class LlmDecisionRequestCompactor {

    private final LlmDecisionRequestFactory factory;
    private final JacksonDecisionRequestSerializer serializer;
    private final DecisionTurnPolicy policy;
    private final int characterBudget;

    public LlmDecisionRequestCompactor(
            LlmDecisionRequestFactory factory,
            JacksonDecisionRequestSerializer serializer,
            DecisionTurnPolicy policy
    ) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.characterBudget = policy.maxSerializedCharacters();
    }

    /**
     * The bounds the turn runs under.
     *
     * <p>Includes the trigger bound the coordinator batches by, so batching
     * stays configured rather than hardcoded.</p>
     */
    public DecisionTurnPolicy policy() {
        return policy;
    }

    public int characterBudget() {
        return characterBudget;
    }

    public Result prepare(DecisionTurnInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        LlmDecisionRequestFactory.Prepared prepared = factory.create(inputs);
        LlmDecisionRequest full = prepared.request();
        String serialized = serializer.serialize(full);
        int original = serialized.length();
        if (original <= characterBudget) {
            return new Result.Fitted(
                    full,
                    prepared.evidence(),
                    serialized,
                    false,
                    original
            );
        }

        LlmDecisionRequest mandatory = withoutContext(full);
        serialized = serializer.serialize(mandatory);
        if (serialized.length() <= characterBudget) {
            return new Result.Fitted(
                    mandatory,
                    prepared.evidence(),
                    serialized,
                    true,
                    original
            );
        }

        return new Result.DoesNotFit(
                inputs.turnSequence(),
                inputs.triggers().getFirst().busSequence(),
                inputs.triggers().getLast().busSequence(),
                serialized.length(),
                characterBudget,
                original,
                sectionWeights(mandatory)
        );
    }

    /** The request with its one optional rung already dropped. */
    public LlmDecisionRequest mandatoryOnly(DecisionTurnInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        return withoutContext(factory.create(inputs).request());
    }

    /** What that mandatory request costs, in characters. */
    public int mandatoryCharacterCount(DecisionTurnInputs inputs) {
        return serializer.serialize(mandatoryOnly(inputs)).length();
    }

    /** What the mandatory content actually cost, largest first. */
    private List<Result.SectionWeight> sectionWeights(
            LlmDecisionRequest mandatory
    ) {
        List<Result.SectionWeight> weights = new ArrayList<>(3);
        for (String section : List.of(
                DecisionSections.EVENTS,
                DecisionSections.CHANGES,
                DecisionSections.TRAJECTORY
        )) {
            weights.add(new Result.SectionWeight(
                    section,
                    serializer.serializeSection(mandatory, section).length()
            ));
        }
        weights.sort((first, second) -> Integer.compare(
                second.characterCount(),
                first.characterCount()
        ));
        return List.copyOf(weights);
    }

    private static LlmDecisionRequest withoutContext(
            LlmDecisionRequest source
    ) {
        if (source.context().isEmpty()) {
            return source;
        }
        return new LlmDecisionRequest(
                source.events(),
                source.changes(),
                List.of(),
                // Bounded at three predecessors and three predictions, so it
                // cannot be the reason a turn overflows and dropping it would
                // buy nothing worth the loss of a repeat being recognisable.
                source.trajectory(),
                true
        );
    }

    /** The outcome of one preparation attempt. */
    public sealed interface Result {

        /** A request that fits, and the exact JSON that will be sent. */
        record Fitted(
                LlmDecisionRequest request,
                DecisionEvidence evidence,
                String serializedJson,
                boolean compactionApplied,
                int originalCharacterCount
        ) implements Result {

            public Fitted {
                Objects.requireNonNull(request, "request");
                Objects.requireNonNull(evidence, "evidence");
                Objects.requireNonNull(serializedJson, "serializedJson");
            }

            public int finalCharacterCount() {
                return serializedJson.length();
            }
        }

        /**
         * The mandatory content alone exceeds the budget.
         *
         * <p>No smaller request exists that is still true, so none is produced
         * and the turn fails closed.</p>
         */
        record DoesNotFit(
                long turnSequence,
                long firstTriggerBusSequence,
                long finalTriggerBusSequence,
                int mandatoryCharacterCount,
                int configuredCharacterBudget,
                int originalCharacterCount,
                List<SectionWeight> largestMandatorySections
        ) implements Result {

            public DoesNotFit {
                largestMandatorySections = List.copyOf(Objects.requireNonNull(
                        largestMandatorySections,
                        "largestMandatorySections"
                ));
                if (mandatoryCharacterCount <= configuredCharacterBudget) {
                    throw new IllegalArgumentException(
                            "a fitting request must not report DoesNotFit"
                    );
                }
            }

            public int overshootCharacters() {
                return mandatoryCharacterCount - configuredCharacterBudget;
            }

            /**
             * The same failure, in the form the turn reports it as.
             *
             * <p>Projected here rather than assembled by the coordinator or
             * the trace writer: the typed failure knows its own sizing, and a
             * second party restating it is a second place for the arithmetic
             * to differ. Nothing is reinterpreted — every field is carried
             * across, and the overshoot the neutral record re-checks is the
             * one computed above.</p>
             */
            public ContextOverflow contextOverflow() {
                return new ContextOverflow(
                        turnSequence,
                        firstTriggerBusSequence,
                        finalTriggerBusSequence,
                        mandatoryCharacterCount,
                        configuredCharacterBudget,
                        originalCharacterCount,
                        overshootCharacters(),
                        largestMandatorySections.stream()
                                .map(section ->
                                        new ContextOverflow.SectionWeight(
                                                section.section(),
                                                section.characterCount()
                                        ))
                                .toList()
                );
            }
        }

        /** One mandatory section and the characters it consumed. */
        record SectionWeight(String section, int characterCount) {

            public SectionWeight {
                Objects.requireNonNull(section, "section");
                if (section.isBlank()) {
                    throw new IllegalArgumentException(
                            "section must not be blank"
                    );
                }
                if (characterCount < 0) {
                    throw new IllegalArgumentException(
                            "characterCount must be nonnegative"
                    );
                }
            }
        }
    }
}
