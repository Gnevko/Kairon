package kairon.observer.decision;

import kairon.observer.decision.DecisionEventProjector.ProjectedEvent;
import kairon.projection.ProjectedObservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds one decision request from fixed turn inputs.
 *
 * <p>Reads {@link DecisionTurnInputs} and nothing else — no projector, no graph
 * service, no bus, no clock — so the same inputs always produce an equal
 * request. It recomputes no delta and no probability: every before and after,
 * and every transition figure, was fixed inside the projection boundary and is
 * copied verbatim from the captured envelope.</p>
 *
 * <p>Order matters here. Events are projected first because changes are judged
 * against what the events already say, and context is selected last because it
 * is judged against both — through one {@link StatedFacts} built from the
 * events and extended with the changes, so all three sections answer to one
 * definition of what has already been said. There is no fourth section: the
 * visit's recent events and the transition model's forecast used to be built
 * here too, and are gone — no comment in any measured run rested on either.</p>
 */
public final class LlmDecisionRequestFactory {

    private final DecisionEventProjector eventProjector;
    private final DecisionChangeSelector changeSelector;
    private final DecisionContextSelector contextSelector;

    /**
     * A factory that names organisms through {@code names}.
     *
     * <p>One naming for all three collaborators on purpose: an organism named
     * one way in an event and another in the context beside it is the defect
     * ADR-0028 removed, and the way to keep it removed is that there is one
     * object deciding.</p>
     */
    public LlmDecisionRequestFactory(DecisionOrganicNames names) {
        Objects.requireNonNull(names, "names");
        this.eventProjector = new DecisionEventProjector(names);
        this.changeSelector = new DecisionChangeSelector(names);
        this.contextSelector = new DecisionContextSelector(names);
    }

    /**
     * A factory with no organic registry behind it.
     *
     * <p>Exactly the {@code bio.registryFile: null} configuration: every
     * organism is named by the word the journal itself carried, which is what
     * every organism was named by before ADR-0028.</p>
     */
    public LlmDecisionRequestFactory() {
        this(DecisionOrganicNames.withoutRegistry());
    }

    public LlmDecisionRequest create(DecisionTurnInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        List<ProjectedEvent> projected =
                new ArrayList<>(inputs.triggers().size());
        int localId = 1;
        for (ProjectedObservation trigger : inputs.triggers()) {
            projected.add(eventProjector.project(localId++, trigger));
        }
        // Built once, from every projected event of this turn, and read the
        // same way by both selectors. They used to answer "has this already
        // been said?" with different machinery, and a boolean or a number was
        // therefore never suppressed by the second of them.
        StatedFacts stated = StatedFacts.ofEvents(projected);
        List<LlmDecisionRequest.Change> changes =
                changeSelector.select(inputs, projected, stated);
        List<LlmDecisionRequest.ContextGroup> context =
                contextSelector.select(
                        inputs.finalTrigger().currentState(),
                        inputs.finalTrigger().systemRegistry(),
                        projected,
                        changes,
                        stated
                );
        return new LlmDecisionRequest(
                projected.stream().map(ProjectedEvent::event).toList(),
                changes,
                context,
                // The only real loss the pipeline can report before the budget
                // is consulted: observations folded away because the
                // accumulator hit its memory bound.
                inputs.semanticEffects().bounded()
        );
    }
}
