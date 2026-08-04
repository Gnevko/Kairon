package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.graph.BehaviorGraphIds;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.OccurrenceTransition;
import kairon.behavior.model.SystemEpisode;
import kairon.llm.LlmClient;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observer.ObserverTurnListener;
import kairon.observer.ObserverTurnListener.ObservationEffect;
import kairon.observer.ObserverTurnListener.ObservationEffectChanged;
import kairon.projection.ProjectedObservation;
import kairon.semantics.SemanticObservationEnvelope;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One observation sequence, run through the production pipeline, observed at
 * every layer.
 *
 * <p>Not a second pipeline. It composes {@link DecisionProductionPipeline},
 * which is the real bus, the real projection coordinator, the real behaviour
 * graph, the real semantic envelope, the real observer subscriber and turn
 * coordinator, the real request factory and the real serializer. Only three
 * things are substituted, and none of them can change what the model is sent:
 * the source of observations, the graph store (in-memory, per instance) and the
 * provider (a stub that answers {@code SILENT}).</p>
 *
 * <h2>Why it exists</h2>
 * <p>Every defect this project has had to fix twice had the same shape: one
 * layer was right and another was wrong, and each layer's own tests were green.
 * A harness that can only see one layer cannot state the contract that was
 * actually broken. This one produces a {@link PipelineTrace} covering canonical
 * state, semantic effects, episodes, occurrences, transitions, the cursor,
 * observer admission, provider calls and the exact serialized request.</p>
 *
 * <h2>Batch boundary</h2>
 * <p>{@link #closeBatch()} is the deterministic boundary. It waits for the
 * projection to go idle — so every observation has been projected and every
 * observer command posted — and then for the observer to go idle, which
 * completes once the scheduled turn has run. The batch itself closes through
 * production batching: the harness shortens the configured quiet period rather
 * than changing how batching works.</p>
 *
 * <p>Deliberately <em>not</em> the two mechanisms earlier tests reached for. A
 * replay-exhaustion signal also completes the {@code SystemEpisode}, which
 * confounds "the observer declined" with "there was no visit"; and
 * {@code DecisionTurnPolicy(1, …)} closes after every single trigger, so a
 * multi-trigger batch cannot be expressed at all. Both are avoided here, and
 * the shortened quiet period is the whole workaround.</p>
 */
final class SemanticPipelineHarness implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The batch quiet period, in milliseconds.
     *
     * <p>Long enough that observations published back to back in one test stay
     * in one batch, short enough that a closed batch costs a fraction of a
     * second. Nothing about batching semantics changes: the coordinator still
     * closes on the quiet period, the maximum batch age or the trigger cap,
     * exactly as in production.</p>
     */
    private static final long QUIET_PERIOD_MS = 250L;

    private final DecisionProductionPipeline pipeline;
    private final boolean graphEnabled;

    private SemanticPipelineHarness(
            DecisionProductionPipeline pipeline,
            boolean graphEnabled
    ) {
        this.pipeline = pipeline;
        this.graphEnabled = graphEnabled;
    }

    static SemanticPipelineHarness create(Path directory) {
        return create(directory, HarnessOptions.standard());
    }

    static SemanticPipelineHarness create(
            Path directory,
            HarnessOptions options
    ) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(options, "options");
        return new SemanticPipelineHarness(
                new DecisionProductionPipeline(
                        directory,
                        new DecisionProductionPipeline.Options(
                                options.graphEnabled(),
                                options.turnPolicy(),
                                QUIET_PERIOD_MS,
                                60_000L
                        )
                ),
                options.graphEnabled()
        );
    }

    /**
     * How one harness instance is wired.
     *
     * <p>Only what a contract test actually needs. The batch policy stays the
     * production one unless a test has a reason to say otherwise, so batching
     * is exercised rather than bypassed.</p>
     */
    record HarnessOptions(boolean graphEnabled, DecisionTurnPolicy turnPolicy) {

        static HarnessOptions standard() {
            return new HarnessOptions(true, DecisionTurnPolicy.production());
        }

        static HarnessOptions withoutGraph() {
            return new HarnessOptions(false, DecisionTurnPolicy.production());
        }
    }

    SemanticPipelineHarness journal(
            ObservationCaptureMode captureMode,
            String rawJournalJson
    ) {
        pipeline.journal(captureMode, rawJournalJson);
        return this;
    }

    /** A replayed journal record, which is the ordinary case for a test. */
    SemanticPipelineHarness journal(String rawJournalJson) {
        return journal(ObservationCaptureMode.REPLAY, rawJournalJson);
    }

    SemanticPipelineHarness status(
            ObservationCaptureMode captureMode,
            String timestamp,
            long flags,
            int guiFocus
    ) {
        pipeline.status(captureMode, timestamp, flags, guiFocus);
        return this;
    }

    /**
     * Closes the current batch and waits for everything it started.
     *
     * <p>Projection first, then the observer: a turn cannot be waited for
     * before the observations that would join it have been projected.</p>
     */
    SemanticPipelineHarness closeBatch() {
        try {
            pipeline.settle();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "closing the batch failed",
                    failure
            );
        }
        return this;
    }

    /**
     * Waits for the projection only, leaving the batch open.
     *
     * <p>For a sequence that must reach the graph without a turn firing in the
     * middle of it.</p>
     */
    SemanticPipelineHarness settleProjection() {
        try {
            pipeline.settleProjection();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "settling the projection failed",
                    failure
            );
        }
        return this;
    }

    /** Everything every layer produced, as an immutable snapshot. */
    PipelineTrace trace() {
        List<ProjectedObservation> projections =
                pipeline.capturedProjections();
        Map<EventOccurrenceId, Long> occurrenceOwners =
                occurrenceOwners(projections);
        List<PipelineTrace.ObservationRecord> observations =
                new ArrayList<>(projections.size());
        for (ProjectedObservation projected : projections) {
            SemanticObservationEnvelope envelope =
                    projected.semanticEnvelope();
            // Read from the envelope, not from the publication: what this trace
            // reports is what the metadata looks like where downstream would
            // consume it, which is the whole point of carrying it there.
            observations.add(new PipelineTrace.ObservationRecord(
                    projected.busSequence(),
                    projected.trigger().observationId(),
                    envelope.captureMode(),
                    envelope.rawObservationType(),
                    envelope.sourceRole(),
                    envelope.effectRetention(),
                    projected.currentState(),
                    envelope.stateChanges(),
                    envelope.structuredFacts().size(),
                    mintedOccurrence(projected, occurrenceOwners)
            ));
        }
        return new PipelineTrace(
                graphEnabled,
                observations,
                episodes(occurrenceOwners),
                edges(),
                cursor(),
                turns(),
                projections.isEmpty()
                        ? Optional.empty()
                        : Optional.of(projections.getLast().currentState())
        );
    }

    /** The raw pipeline, for a test that needs something not yet traced. */
    DecisionProductionPipeline pipeline() {
        return pipeline;
    }

    @Override
    public void close() {
        pipeline.close();
    }

    // ------------------------------------------------------------ recording

    /**
     * Which observation minted which occurrence.
     *
     * <p>Derived the same way {@code DecisionOccurrenceScope} derives it: the
     * id the graph <em>would</em> mint for an observation is recomputed and
     * compared with the ids it actually holds. Never by adjacency, never by
     * timestamp, and never by assuming that an {@code APPLIED} status means
     * this observation is the one that changed the graph.</p>
     */
    private Map<EventOccurrenceId, Long> occurrenceOwners(
            List<ProjectedObservation> projections
    ) {
        Map<EventOccurrenceId, Long> owners = new HashMap<>();
        Optional<GraphId> graphId = pipeline.optionalGraphId();
        if (graphId.isEmpty()) {
            return Map.of();
        }
        for (ProjectedObservation projected : projections) {
            owners.put(
                    BehaviorGraphIds.journalOccurrence(
                            graphId.orElseThrow(),
                            projected.trigger().observationId()
                    ),
                    projected.busSequence()
            );
        }
        return Map.copyOf(owners);
    }

    private Optional<EventOccurrenceId> mintedOccurrence(
            ProjectedObservation projected,
            Map<EventOccurrenceId, Long> owners
    ) {
        Optional<GraphId> graphId = pipeline.optionalGraphId();
        if (graphId.isEmpty() || owners.isEmpty()) {
            return Optional.empty();
        }
        EventOccurrenceId candidate = BehaviorGraphIds.journalOccurrence(
                graphId.orElseThrow(),
                projected.trigger().observationId()
        );
        for (SystemEpisode episode
                : pipeline.graph().episodes(graphId.orElseThrow())) {
            for (EventOccurrence occurrence : episode.timeline()) {
                if (occurrence.id().equals(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private List<PipelineTrace.EpisodeView> episodes(
            Map<EventOccurrenceId, Long> owners
    ) {
        Optional<GraphId> graphId = pipeline.optionalGraphId();
        if (graphId.isEmpty()) {
            return List.of();
        }
        List<PipelineTrace.EpisodeView> views = new ArrayList<>();
        for (SystemEpisode episode
                : pipeline.graph().episodes(graphId.orElseThrow())) {
            List<PipelineTrace.OccurrenceView> occurrences =
                    new ArrayList<>(episode.timeline().size());
            for (EventOccurrence occurrence : episode.timeline()) {
                occurrences.add(new PipelineTrace.OccurrenceView(
                        occurrence.id(),
                        occurrence.eventType(),
                        occurrence.episodeSequence(),
                        occurrence.source(),
                        Optional.ofNullable(owners.get(occurrence.id()))
                ));
            }
            List<PipelineTrace.TransitionView> transitions =
                    new ArrayList<>(episode.occurrenceTransitions().size());
            for (OccurrenceTransition transition
                    : episode.occurrenceTransitions()) {
                transitions.add(new PipelineTrace.TransitionView(
                        transition.fromEventType(),
                        transition.toEventType()
                ));
            }
            views.add(new PipelineTrace.EpisodeView(
                    episode.id(),
                    episode.entrySource(),
                    episode.systemAddress(),
                    episode.active(),
                    occurrences,
                    transitions
            ));
        }
        return List.copyOf(views);
    }

    private List<PipelineTrace.EdgeView> edges() {
        Optional<GraphId> graphId = pipeline.optionalGraphId();
        if (graphId.isEmpty()) {
            return List.of();
        }
        return pipeline.graph().graph(graphId.orElseThrow())
                .map(graph -> graph.edges().stream()
                        .map(edge -> new PipelineTrace.EdgeView(
                                edge.key().fromEventType(),
                                edge.key().toEventType(),
                                edge.globalCounter().rawCount()
                        ))
                        .toList())
                .orElseGet(List::of);
    }

    private Optional<PipelineTrace.CursorView> cursor() {
        return pipeline.optionalGraphId()
                .flatMap(graphId -> pipeline.graph().cursor(graphId))
                .map(cursor -> new PipelineTrace.CursorView(
                        cursor.eventType(),
                        cursor.occurrenceId()
                ));
    }

    /**
     * One view per provider call, bound to the triggers the coordinator used.
     *
     * <p>The binding is exact rather than positional where the production
     * listener supplies it: {@code DecisionResolved} names the turn each
     * provider call belongs to, and {@code NEW_IN_FLIGHT} names every trigger
     * bound to that turn. When the two disagree in count — a turn that failed
     * closed carries no decision — the trigger list is reported empty rather
     * than guessed.</p>
     */
    private List<PipelineTrace.TurnView> turns() {
        List<LlmClient.ModelInput> inputs = pipeline.modelInputs();
        List<ObserverTurnListener.DecisionResolved> decisions =
                pipeline.decisions();
        Map<Long, List<Long>> triggersByTurn = new HashMap<>();
        for (ObservationEffectChanged effect
                : pipeline.observationEffects()) {
            if (effect.effect() == ObservationEffect.NEW_IN_FLIGHT
                    && effect.turnSequence() != null) {
                triggersByTurn
                        .computeIfAbsent(
                                effect.turnSequence(),
                                ignored -> new ArrayList<>()
                        )
                        .add(effect.busSequence());
            }
        }
        List<PipelineTrace.TurnView> views = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            String userMessage = inputs.get(index).userMessage();
            String document = userMessage.substring(userMessage.indexOf('{'));
            JsonNode parsed;
            try {
                parsed = JSON.readTree(document);
            } catch (Exception failure) {
                throw new IllegalStateException(userMessage, failure);
            }
            long turnSequence = index < decisions.size()
                    ? decisions.get(index).turnSequence()
                    : -1L;
            List<Integer> ids = new ArrayList<>();
            List<String> kinds = new ArrayList<>();
            parsed.path("events").forEach(event -> {
                ids.add(event.path("id").intValue());
                kinds.add(event.path("kind").textValue());
            });
            views.add(new PipelineTrace.TurnView(
                    turnSequence,
                    document,
                    parsed,
                    ids,
                    kinds,
                    triggersByTurn.getOrDefault(turnSequence, List.of())
            ));
        }
        return List.copyOf(views);
    }
}
