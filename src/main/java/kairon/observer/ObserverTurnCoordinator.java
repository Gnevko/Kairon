package kairon.observer;

import kairon.config.KaironConfiguration;
import kairon.llm.LlmClient;
import kairon.llm.LlmClient.LlmTokenUsage;
import kairon.llm.LlmClient.ModelInput;
import kairon.llm.ObserverResponseValidator;
import kairon.llm.ObserverResponseValidator.ValidatedObserverResponse;
import kairon.llm.DecisionPromptFactory;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observer.ObserverTurnListener.ObservationEffect;
import kairon.observer.ObserverTurnListener.ObservationEffectChanged;
import kairon.observer.decision.DecisionTurnInputs;
import kairon.observer.decision.DecisionTurnPolicy;
import kairon.observer.decision.DeliveredModelComment;
import kairon.observer.decision.LlmDecisionRequest;
import kairon.observer.decision.LlmDecisionRequestCompactor;
import kairon.output.CommentSink;
import kairon.output.CommentSink.CommentDeliveryResult;
import kairon.output.CommentSink.ConsoleOutcome;
import kairon.output.CommentSink.SpeechDeliveryResult;
import kairon.output.CommentSink.SpeechOutcome;
import kairon.projection.ProjectedObservation;
import kairon.semantics.SemanticEffectAccumulator;
import kairon.semantics.SemanticObservationEnvelope;
import kairon.speech.SpeechSynthesisClient.SpeechFailureCategory;
import kairon.trace.JsonLinesTurnTraceWriter;
import kairon.trace.JsonLinesTurnTraceWriter.ContextOverflowTrace;
import kairon.trace.JsonLinesTurnTraceWriter.ProviderTrace;
import kairon.trace.JsonLinesTurnTraceWriter.TurnTrace;
import kairon.turn.evidence.DecisionEvidence;
import kairon.turn.overflow.ContextOverflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns batching and delivery for the sole snapshot-based LLM context path.
 *
 * <p>The queue contains complete immutable projection envelopes. No journal
 * history or late state/graph lookup exists here.</p>
 *
 * <p>The production contract is the decision request and nothing else. There is
 * no version selector, no fallback and no second document: a turn either
 * produces one request or, when its mandatory content exceeds the budget, no
 * request at all.</p>
 */
public final class ObserverTurnCoordinator implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ObserverTurnCoordinator.class
    );
    private static final int PREVIOUS_COMMENT_LIMIT = 3;

    private final Object lifecycleGate = new Object();
    private final ScheduledExecutorService executor;
    private final Duration quietPeriod;
    private final Duration maximumBatchAge;
    private final String outputLanguage;
    private final LlmDecisionRequestCompactor decisionRequestCompactor;
    private final DecisionTurnPolicy turnPolicy;
    private final DecisionPromptFactory promptFactory;
    private final ObserverResponseValidator responseValidator;
    private final LlmClient llmClient;
    private final CommentSink commentSink;
    private final JsonLinesTurnTraceWriter traceWriter;
    private final ObserverTurnListener turnListener;
    private final Deque<QueuedProjection> newQueue = new ArrayDeque<>();
    private final Deque<DeliveredModelComment> deliveredComments =
            new ArrayDeque<>();
    private final List<CompletableFuture<Void>> idleWaiters =
            new ArrayList<>();


    /**
     * Semantic effects observed since the previous turn.
     *
     * <p>Confined to the coordinator executor: every mutation happens on the
     * {@code observer-coordinator} thread, which is also the thread that
     * drains it, so no concurrent collection is needed and replay stays
     * deterministic.</p>
     */
    private final SemanticEffectAccumulator semanticEffects =
            new SemanticEffectAccumulator();

    private SemanticEffectAccumulator.Drained lastDrainedSemanticEffects =
            SemanticEffectAccumulator.Drained.none();

    private ScheduledFuture<?> eligibilityTask;
    private CompletableFuture<LlmClient.LlmResponse> activeRequest;
    private CompletableFuture<CommentDeliveryResult> activeDelivery;
    private ActiveTurn activeTurn;
    private boolean replayExhausted;
    private volatile boolean shuttingDown;
    private CompletableFuture<ObserverShutdownReport> shutdownStage;
    private int shutdownDiscardedCount;
    private int completedTurnCount;
    private long nextTurnSequence;

    public ObserverTurnCoordinator(
            KaironConfiguration.ObserverConfiguration configuration,
            LlmDecisionRequestCompactor decisionRequestCompactor,
            DecisionPromptFactory promptFactory,
            LlmClient llmClient,
            CommentSink commentSink,
            JsonLinesTurnTraceWriter traceWriter
    ) {
        this(
                configuration,
                decisionRequestCompactor,
                promptFactory,
                llmClient,
                commentSink,
                traceWriter,
                ObserverTurnListener.noOp()
        );
    }

    public ObserverTurnCoordinator(
            KaironConfiguration.ObserverConfiguration configuration,
            LlmDecisionRequestCompactor decisionRequestCompactor,
            DecisionPromptFactory promptFactory,
            LlmClient llmClient,
            CommentSink commentSink,
            JsonLinesTurnTraceWriter traceWriter,
            ObserverTurnListener turnListener
    ) {
        Objects.requireNonNull(configuration, "configuration");
        this.quietPeriod = Duration.ofMillis(
                configuration.quietPeriodMs()
        );
        this.maximumBatchAge = Duration.ofMillis(
                configuration.maximumBatchAgeMs()
        );
        this.outputLanguage = configuration.outputLanguage();
        this.decisionRequestCompactor = Objects.requireNonNull(
                decisionRequestCompactor,
                "decisionRequestCompactor"
        );
        this.turnPolicy = decisionRequestCompactor.policy();
        this.promptFactory = Objects.requireNonNull(
                promptFactory,
                "promptFactory"
        );
        this.responseValidator = new ObserverResponseValidator();
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.commentSink = Objects.requireNonNull(
                commentSink,
                "commentSink"
        );
        this.traceWriter = Objects.requireNonNull(
                traceWriter,
                "traceWriter"
        );
        this.turnListener = Objects.requireNonNull(
                turnListener,
                "turnListener"
        );
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "observer-coordinator");
            thread.setDaemon(false);
            return thread;
        });
    }

    public void post(ObserverCommand command) {
        Objects.requireNonNull(command, "command");
        synchronized (lifecycleGate) {
            if (shuttingDown) {
                throw new RejectedExecutionException(
                        "observer coordinator is shutting down"
                );
            }
            executor.execute(() -> apply(command));
        }
    }

    /**
     * Completes after commands ordered before this barrier update local state.
     */
    public CompletionStage<Void> awaitApplied() {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        executor.execute(() -> barrier.complete(null));
        return barrier;
    }

    /**
     * Completes after all earlier NEW triggers reach terminal turn state.
     */
    public CompletionStage<Void> awaitIdle() {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        executor.execute(() -> {
            if (newQueue.isEmpty() && activeTurn == null) {
                barrier.complete(null);
            } else {
                idleWaiters.add(barrier);
            }
        });
        return barrier;
    }

    public CompletionStage<ObserverSnapshot> snapshot() {
        CompletableFuture<ObserverSnapshot> result =
                new CompletableFuture<>();
        executor.execute(() -> result.complete(new ObserverSnapshot(
                newQueue.size(),
                activeRequest != null,
                completedTurnCount,
                commentTexts(deliveredComments)
        )));
        return result;
    }

    /**
     * The semantic effects drained into the most recently started turn.
     *
     * <p>Phase B foundation: the effects are accumulated, bounded and drained,
     * but are not yet part of model input. Reading them goes through the
     * coordinator executor, so no caller observes partially drained state.</p>
     */
    public CompletionStage<SemanticEffectAccumulator.Drained>
            lastDrainedSemanticEffects() {
        CompletableFuture<SemanticEffectAccumulator.Drained> result =
                new CompletableFuture<>();
        executor.execute(
                () -> result.complete(lastDrainedSemanticEffects)
        );
        return result;
    }

    /** Semantic effects still held for a later turn. */
    public CompletionStage<Integer> pendingSemanticEffectCount() {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        executor.execute(() -> result.complete(
                semanticEffects.pendingEnvelopeCount()
                        + semanticEffects.pendingCoalescedChangeCount()
        ));
        return result;
    }

    public CompletionStage<ObserverShutdownReport> shutdown() {
        synchronized (lifecycleGate) {
            if (shutdownStage != null) {
                return shutdownStage;
            }
            shutdownStage = new CompletableFuture<>();
            shuttingDown = true;
            try {
                executor.execute(this::beginShutdown);
            } catch (RejectedExecutionException rejection) {
                shutdownStage.completeExceptionally(rejection);
            }
            return shutdownStage;
        }
    }

    @Override
    public void close() {
        try {
            shutdown().toCompletableFuture().join();
        } catch (CompletionException exception) {
            throw new IllegalStateException(
                    "observer coordinator shutdown failed",
                    exception.getCause()
            );
        }
    }

    private void apply(ObserverCommand command) {
        switch (command) {
            case ObserverCommand.QueueNewObservation queued ->
                    queueNew(queued.observation());
            case ObserverCommand.RecordSemanticEffect effect ->
                    semanticEffects.record(effect.envelope());
            case ObserverCommand.ReplaySourceExhausted ignored -> {
                replayExhausted = true;
                scheduleOrStartTurn();
            }
        }
        completeIdleWaitersIfIdle();
    }

    private void queueNew(ProjectedObservation observation) {
        // The semantic effect arrives on its own command, posted before this
        // one. Recording here as well would double-count the trigger.
        long now = System.nanoTime();
        newQueue.addLast(new QueuedProjection(observation, now));
        notifyObservationEffect(
                observation,
                ObservationEffect.NEW_QUEUED,
                null
        );
        scheduleOrStartTurn();
    }

    private void scheduleOrStartTurn() {
        if (newQueue.isEmpty() || activeTurn != null || shuttingDown) {
            return;
        }
        long now = System.nanoTime();
        long dueAt;
        if (replayExhausted
                || newQueue.size() >= turnPolicy.maxTriggers()) {
            dueAt = now;
        } else {
            long firstArrival = newQueue.getFirst().queuedAtNanos();
            long lastArrival = newQueue.getLast().queuedAtNanos();
            dueAt = Math.min(
                    saturatingAdd(
                            lastArrival,
                            quietPeriod.toNanos()
                    ),
                    saturatingAdd(
                            firstArrival,
                            maximumBatchAge.toNanos()
                    )
            );
        }
        long delay = Math.max(0L, dueAt - now);
        if (eligibilityTask != null) {
            eligibilityTask.cancel(false);
        }
        eligibilityTask = executor.schedule(
                this::startTurn,
                delay,
                TimeUnit.NANOSECONDS
        );
    }

    private void startTurn() {
        eligibilityTask = null;
        if (newQueue.isEmpty() || activeTurn != null || shuttingDown) {
            return;
        }
        int triggerCount = Math.min(
                newQueue.size(),
                turnPolicy.maxTriggers()
        );
        List<ProjectedObservation> triggers =
                new ArrayList<>(triggerCount);
        for (int index = 0; index < triggerCount; index++) {
            triggers.add(newQueue.removeFirst().observation());
        }
        long turnSequence = ++nextTurnSequence;
        List<DeliveredModelComment> comments =
                List.copyOf(deliveredComments);
        // Drained in the same critical section that fixes the trigger batch,
        // through the final trigger only: later effects belong to a later turn.
        lastDrainedSemanticEffects = semanticEffects.drainThrough(
                triggers.getLast().busSequence()
        );
        activeTurn = ActiveTurn.preparing(
                turnSequence,
                List.copyOf(triggers),
                comments
        );
        for (ProjectedObservation trigger : triggers) {
            notifyObservationEffect(
                    trigger,
                    ObservationEffect.NEW_IN_FLIGHT,
                    turnSequence
            );
        }

        LlmDecisionRequestCompactor.Result prepared;
        ModelInput exactInput;
        try {
            prepared = decisionRequestCompactor.prepare(new DecisionTurnInputs(
                    turnSequence,
                    triggers,
                    lastDrainedSemanticEffects,
                    comments
            ));
        } catch (RuntimeException preparationFailure) {
            finishPreparationFailure(preparationFailure);
            return;
        }
        if (prepared
                instanceof LlmDecisionRequestCompactor.Result.DoesNotFit fail) {
            // Fail closed. No provider call, no comment, no synthesised
            // silence, and no retry at a different budget or with less
            // content: a turn either carries its mandatory semantics or it
            // does not happen.
            finishContextTooLarge(fail.contextOverflow());
            return;
        }
        LlmDecisionRequestCompactor.Result.Fitted fitted =
                (LlmDecisionRequestCompactor.Result.Fitted) prepared;
        try {
            if (fitted.compactionApplied()) {
                LOGGER.warn(
                        "LLM_DECISION_REQUEST_COMPACTED turnSequence={} "
                                + "triggerCount={} originalCharacters={} "
                                + "finalCharacters={}",
                        turnSequence,
                        triggers.size(),
                        fitted.originalCharacterCount(),
                        fitted.finalCharacterCount()
                );
            }
            exactInput = promptFactory.create(
                    outputLanguage,
                    fitted.serializedJson()
            );
            activeTurn = activeTurn.prepared(fitted, exactInput);
        } catch (RuntimeException preparationFailure) {
            finishPreparationFailure(preparationFailure);
            return;
        }

        long startedAt = System.nanoTime();
        try {
            activeRequest = llmClient.complete(exactInput)
                    .toCompletableFuture();
        } catch (RuntimeException synchronousFailure) {
            finishFailedTurn(
                    null,
                    responseValidator.modelCallFailed(synchronousFailure),
                    LlmTokenUsage.unavailable(),
                    elapsedMillis(startedAt)
            );
            return;
        }
        activeRequest.whenComplete((response, failure) -> {
            try {
                executor.execute(() -> completeModelTurn(
                        startedAt,
                        response,
                        failure
                ));
            } catch (RejectedExecutionException ignored) {
                // Rejection is possible only after terminal shutdown.
            }
        });
    }

    /**
     * Ends a turn whose mandatory context exceeded the budget.
     *
     * <p>The batch is consumed exactly once: its triggers are not returned to
     * the queue and the drained semantic effects are not handed back to the
     * accumulator, because the next turn's evidence scope cannot contain this
     * turn's bus sequences. Previous-comment memory is untouched, no speech is
     * attempted, and no synthetic decision is invented.</p>
     */
    private void finishContextTooLarge(ContextOverflow overflow) {
        ActiveTurn completed = activeTurn;
        if (completed == null) {
            return;
        }
        LOGGER.warn(
                "LLM_DECISION_CONTEXT_TOO_LARGE turnSequence={} "
                        + "triggerCount={} mandatoryCharacters={} "
                        + "budget={} overshoot={}",
                overflow.turnSequence(),
                completed.triggers().size(),
                overflow.mandatoryCharacterCount(),
                overflow.configuredCharacterBudget(),
                overflow.overshootCharacters()
        );
        ValidatedObserverResponse validated =
                ObserverResponseValidator.contextTooLarge(
                        ContextOverflow.DIAGNOSTIC_MESSAGE
                );
        notifyDecisionResolved(null, validated, 0L);
        CommentDeliveryResult delivery = CommentDeliveryResult.notAttempted(
                commentSink.speechDescriptor()
        );
        traceWriter.append(turnTrace(
                completed,
                overflow,
                null,
                validated,
                LlmTokenUsage.unavailable(),
                0L,
                delivery,
                null
        ));
        notifyTurnCompleted(delivery, null);
        for (ProjectedObservation trigger : completed.triggers()) {
            notifyObservationEffect(
                    trigger,
                    ObservationEffect.NEW_FAILED,
                    completed.turnSequence()
            );
        }
        completedTurnCount++;
        clearActiveTurn();
    }

    private void finishPreparationFailure(RuntimeException failure) {
        LOGGER.error(
                "LLM_DECISION_TURN_PREPARATION_FAILED turnSequence={} "
                        + "category={}",
                activeTurn.turnSequence(),
                failure.getClass().getSimpleName(),
                failure
        );
        ValidatedObserverResponse validated =
                responseValidator.modelCallFailed(failure);
        notifyDecisionResolved(null, validated, 0L);
        finishWithoutModelCall(validated);
    }

    private void completeModelTurn(
            long startedAt,
            LlmClient.LlmResponse response,
            Throwable failure
    ) {
        if (activeTurn == null) {
            return;
        }
        activeRequest = null;
        if (failure != null) {
            finishFailedTurn(
                    null,
                    responseValidator.modelCallFailed(unwrap(failure)),
                    LlmTokenUsage.unavailable(),
                    elapsedMillis(startedAt)
            );
            return;
        }
        if (response == null) {
            finishFailedTurn(
                    null,
                    responseValidator.modelCallFailed(
                            new IllegalStateException(
                                    "LLM client completed without a response"
                            )
                    ),
                    LlmTokenUsage.unavailable(),
                    elapsedMillis(startedAt)
            );
            return;
        }

        String rawOutput = response.content();
        ValidatedObserverResponse validated;
        try {
            validated = responseValidator.validate(
                    rawOutput,
                    activeTurn.evidence(),
                    commentTexts(activeTurn.previousComments())
            );
        } catch (RuntimeException validationFailure) {
            validated = new ValidatedObserverResponse(
                    ObserverResponseValidator.Status.INVALID,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of("VALIDATOR_FAILURE"),
                    null
            );
        }
        if (validated.status()
                == ObserverResponseValidator.Status.INVALID) {
            LOGGER.warn(
                    "MODEL_OUTPUT_INVALID violations={}",
                    validated.violations()
            );
        }
        final ValidatedObserverResponse validatedResponse = validated;
        notifyDecisionResolved(
                rawOutput,
                validatedResponse,
                response.latencyMs()
        );

        if (!validatedResponse.isDeliverableComment()) {
            finishTurn(
                    rawOutput,
                    validatedResponse,
                    response.tokenUsage(),
                    response.latencyMs(),
                    null,
                    CommentDeliveryResult.notAttempted(
                            commentSink.speechDescriptor()
                    ),
                    ObservationEffect.NEW_PROCESSED
            );
            return;
        }

        final CompletionStage<CommentDeliveryResult> deliveryStage;
        try {
            deliveryStage = commentSink.deliver(
                    validatedResponse.comment()
            );
        } catch (RuntimeException deliveryFailure) {
            completeCommentDelivery(
                    rawOutput,
                    validatedResponse,
                    response.tokenUsage(),
                    response.latencyMs(),
                    failedDeliveryResult(),
                    deliveryFailure
            );
            return;
        }
        if (deliveryStage == null) {
            completeCommentDelivery(
                    rawOutput,
                    validatedResponse,
                    response.tokenUsage(),
                    response.latencyMs(),
                    failedDeliveryResult(),
                    new IllegalStateException(
                            "comment sink returned no delivery stage"
                    )
            );
            return;
        }
        activeDelivery = deliveryStage.toCompletableFuture();
        activeDelivery.whenComplete((delivery, deliveryFailure) -> {
            try {
                executor.execute(() -> completeCommentDelivery(
                        rawOutput,
                        validatedResponse,
                        response.tokenUsage(),
                        response.latencyMs(),
                        delivery,
                        deliveryFailure
                ));
            } catch (RejectedExecutionException ignored) {
                // Rejection is possible only after terminal shutdown.
            }
        });
    }

    private void completeCommentDelivery(
            String rawOutput,
            ValidatedObserverResponse validated,
            LlmTokenUsage tokenUsage,
            long latencyMs,
            CommentDeliveryResult delivery,
            Throwable failure
    ) {
        if (activeTurn == null) {
            return;
        }
        activeDelivery = null;
        CommentDeliveryResult terminalDelivery =
                failure == null && delivery != null
                        ? delivery
                        : failedDeliveryResult();
        String deliveredComment = null;
        if (terminalDelivery.deliveredForHistory()) {
            deliveredComment = validated.comment();
            // turnSequence and the evidence the comment rested on are both in
            // scope only here; retaining them is what lets a later turn present
            // previous output as the model's own non-authoritative assertion.
            deliveredComments.addLast(new DeliveredModelComment(
                    activeTurn.turnSequence(),
                    deliveredComment,
                    validated.evidenceTriggerBusSequences()
            ));
            while (deliveredComments.size()
                    > PREVIOUS_COMMENT_LIMIT) {
                deliveredComments.removeFirst();
            }
        }
        finishTurn(
                rawOutput,
                validated,
                tokenUsage,
                latencyMs,
                deliveredComment,
                terminalDelivery,
                ObservationEffect.NEW_PROCESSED
        );
    }

    private void finishFailedTurn(
            String rawOutput,
            ValidatedObserverResponse validated,
            LlmTokenUsage tokenUsage,
            long latencyMs
    ) {
        notifyDecisionResolved(rawOutput, validated, latencyMs);
        finishTurn(
                rawOutput,
                validated,
                tokenUsage,
                latencyMs,
                null,
                CommentDeliveryResult.notAttempted(
                        commentSink.speechDescriptor()
                ),
                ObservationEffect.NEW_FAILED
        );
    }

    private void finishWithoutModelCall(
            ValidatedObserverResponse validated
    ) {
        ActiveTurn completed = activeTurn;
        if (completed == null) {
            return;
        }
        for (ProjectedObservation trigger : completed.triggers()) {
            notifyObservationEffect(
                    trigger,
                    ObservationEffect.NEW_FAILED,
                    completed.turnSequence()
            );
        }
        notifyTurnCompleted(
                CommentDeliveryResult.notAttempted(
                        commentSink.speechDescriptor()
                ),
                null
        );
        completedTurnCount++;
        clearActiveTurn();
    }

    private void finishTurn(
            String rawOutput,
            ValidatedObserverResponse validated,
            LlmTokenUsage tokenUsage,
            long latencyMs,
            String deliveredComment,
            CommentDeliveryResult delivery,
            ObservationEffect terminalEffect
    ) {
        ActiveTurn completed = activeTurn;
        if (completed == null || completed.prepared() == null
                || completed.exactInput() == null) {
            return;
        }
        traceWriter.append(turnTrace(
                completed,
                null,
                rawOutput,
                validated,
                tokenUsage,
                latencyMs,
                delivery,
                deliveredComment
        ));
        notifyTurnCompleted(delivery, deliveredComment);
        for (ProjectedObservation trigger : completed.triggers()) {
            notifyObservationEffect(
                    trigger,
                    terminalEffect,
                    completed.turnSequence()
            );
        }
        completedTurnCount++;
        clearActiveTurn();
    }

    /**
     * One trace record for any terminal turn state.
     *
     * <p>The invocation flags are recorded, not inferred: a reader never has to
     * deduce from an absent response whether the provider was reached. An
     * overflow turn carries no context and no model input at all, so both are
     * null and {@code contextOverflow} says why.</p>
     */
    private TurnTrace turnTrace(
            ActiveTurn completed,
            ContextOverflow overflow,
            String rawOutput,
            ValidatedObserverResponse validated,
            LlmTokenUsage tokenUsage,
            long latencyMs,
            CommentDeliveryResult delivery,
            String deliveredComment
    ) {
        boolean providerInvoked = overflow == null;
        return new TurnTrace(
                JsonLinesTurnTraceWriter.TRACE_SCHEMA_VERSION,
                LlmDecisionRequest.CONTEXT_SCHEMA,
                turnOutcome(validated),
                completed.triggerBusSequences(),
                overflow == null
                        ? localEvidence(completed.prepared().evidence())
                        : List.of(),
                overflow == null
                        ? completed.prepared().serializedJson()
                        : null,
                overflow == null
                        ? completed.prepared().serializedJson().length()
                        : 0,
                overflow == null
                        ? null
                        : ContextOverflowTrace.from(overflow),
                providerInvoked,
                deliveredComment != null,
                deliveredComment != null && speechInvoked(delivery),
                ProviderTrace.from(llmClient.provider()),
                overflow == null ? completed.exactInput() : null,
                rawOutput,
                validated,
                tokenUsage,
                Math.max(0L, latencyMs),
                delivery.consoleOutcome().name(),
                delivery.speech().enabled(),
                delivery.speech().provider(),
                delivery.speech().voiceName(),
                instantText(delivery.speechResult().synthesisStartedAt()),
                instantText(delivery.speechResult().synthesisCompletedAt()),
                instantText(delivery.speechResult().playbackStartedAt()),
                instantText(delivery.speechResult().playbackCompletedAt()),
                delivery.speechResult().outcome().name(),
                delivery.speechResult().failureCategory().name(),
                deliveredComment
        );
    }

    /**
     * What each local event id the model was offered actually stood for.
     *
     * <p>Recorded rather than reconstructed. The trace is the only place the
     * two vocabularies meet, and a reader diagnosing a citation must not have
     * to re-derive the mapping from the request's array order.</p>
     */
    private static List<JsonLinesTurnTraceWriter.LocalEvidenceTrace>
            localEvidence(DecisionEvidence evidence) {
        List<JsonLinesTurnTraceWriter.LocalEvidenceTrace> mapping =
                new ArrayList<>(evidence.size());
        for (int localId = 1; localId <= evidence.size(); localId++) {
            mapping.add(new JsonLinesTurnTraceWriter.LocalEvidenceTrace(
                    localId,
                    evidence.busSequenceOf(localId)
            ));
        }
        return List.copyOf(mapping);
    }

    private static String turnOutcome(ValidatedObserverResponse validated) {
        return switch (validated.status()) {
            case VALID -> validated.decision().name();
            case INVALID -> "INVALID_RESPONSE";
            case MODEL_CALL_FAILED -> "MODEL_CALL_FAILED";
            case CONTEXT_TOO_LARGE -> "CONTEXT_TOO_LARGE";
        };
    }

    /** Whether synthesis was actually reached, not merely configured. */
    private static boolean speechInvoked(CommentDeliveryResult delivery) {
        return switch (delivery.speechResult().outcome()) {
            case NOT_REQUESTED, DISABLED -> false;
            default -> true;
        };
    }

    private void clearActiveTurn() {
        activeTurn = null;
        activeRequest = null;
        activeDelivery = null;
        if (shuttingDown) {
            finishShutdownIfPossible();
        } else {
            scheduleOrStartTurn();
            completeIdleWaitersIfIdle();
        }
    }

    private void notifyObservationEffect(
            ProjectedObservation projected,
            ObservationEffect effect,
            Long turnSequence
    ) {
        PublishedObservation<?> observation = projected.trigger();
        try {
            turnListener.onObservationEffectChanged(
                    new ObservationEffectChanged(
                            observation.observationId(),
                            observation.busSequence(),
                            java.time.Instant.now(),
                            effect,
                            turnSequence
                    )
            );
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "OBSERVER_TURN_LISTENER_FAILED "
                            + "phase=observation-effect observationId={} "
                            + "busSequence={} effect={} category={}",
                    observation.observationId(),
                    observation.busSequence(),
                    effect,
                    failure.getClass().getSimpleName()
            );
        }
    }

    private void notifyDecisionResolved(
            String rawOutput,
            ValidatedObserverResponse validated,
            long latencyMs
    ) {
        ActiveTurn turn = activeTurn;
        if (turn == null) {
            return;
        }
        try {
            turnListener.onDecisionResolved(
                    new ObserverTurnListener.DecisionResolved(
                            turn.turnSequence(),
                            java.time.Instant.now(),
                            turn.triggers().size(),
                            validated,
                            rawOutput,
                            Math.max(0L, latencyMs)
                    )
            );
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "OBSERVER_TURN_LISTENER_FAILED phase=decision "
                            + "turnSequence={} category={}",
                    turn.turnSequence(),
                    failure.getClass().getSimpleName()
            );
        }
    }

    private void notifyTurnCompleted(
            CommentDeliveryResult delivery,
            String deliveredComment
    ) {
        ActiveTurn turn = activeTurn;
        if (turn == null) {
            return;
        }
        try {
            turnListener.onTurnCompleted(
                    new ObserverTurnListener.TurnCompleted(
                            turn.turnSequence(),
                            java.time.Instant.now(),
                            delivery,
                            deliveredComment
                    )
            );
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "OBSERVER_TURN_LISTENER_FAILED phase=completion "
                            + "turnSequence={} category={}",
                    turn.turnSequence(),
                    failure.getClass().getSimpleName()
            );
        }
    }

    private void completeIdleWaitersIfIdle() {
        if (!newQueue.isEmpty() || activeTurn != null) {
            return;
        }
        idleWaiters.forEach(waiter -> waiter.complete(null));
        idleWaiters.clear();
    }

    private void beginShutdown() {
        if (eligibilityTask != null) {
            eligibilityTask.cancel(false);
            eligibilityTask = null;
        }
        shutdownDiscardedCount = newQueue.size();
        List<QueuedProjection> discarded = List.copyOf(newQueue);
        newQueue.clear();
        for (QueuedProjection queued : discarded) {
            notifyObservationEffect(
                    queued.observation(),
                    ObservationEffect.NEW_DISCARDED,
                    null
            );
        }
        if (activeRequest != null) {
            activeRequest.cancel(true);
        }
        try {
            commentSink.close();
        } catch (RuntimeException failure) {
            LOGGER.error(
                    "COMMENT_SINK_SHUTDOWN_FAILED cause={}",
                    failure.getClass().getSimpleName()
            );
        }
        LOGGER.info(
                "OBSERVER_SHUTDOWN queuedDiscarded={} activeRequest={}",
                shutdownDiscardedCount,
                activeRequest != null
        );
        completeIdleWaitersIfIdle();
        finishShutdownIfPossible();
    }

    private void finishShutdownIfPossible() {
        if (activeTurn != null) {
            return;
        }
        completeIdleWaitersIfIdle();
        executor.shutdown();
        if (shutdownStage != null && !shutdownStage.isDone()) {
            shutdownStage.complete(new ObserverShutdownReport(
                    shutdownDiscardedCount,
                    completedTurnCount
            ));
        }
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(
                System.nanoTime() - startedAt
        ).toMillis();
    }

    private static String instantText(java.time.Instant value) {
        return value == null ? null : value.toString();
    }

    /** The novelty guard's view of previous comments: text only, oldest first. */
    private static List<String> commentTexts(
            java.util.Collection<DeliveredModelComment> comments
    ) {
        return comments.stream()
                .map(DeliveredModelComment::text)
                .toList();
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if ((failure instanceof CompletionException
                || failure
                instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private CommentDeliveryResult failedDeliveryResult() {
        CommentSink.SpeechDescriptor descriptor =
                commentSink.speechDescriptor();
        return new CommentDeliveryResult(
                descriptor,
                ConsoleOutcome.FAILED,
                new SpeechDeliveryResult(
                        descriptor.enabled()
                                ? SpeechOutcome.SYNTHESIS_FAILED
                                : SpeechOutcome.DISABLED,
                        descriptor.enabled()
                                ? SpeechFailureCategory.INTERNAL
                                : SpeechFailureCategory.NONE,
                        null,
                        null,
                        null,
                        null
                )
        );
    }

    public record ObserverSnapshot(
            int queuedNewCount,
            boolean modelRequestActive,
            int completedTurnCount,
            List<String> previousComments
    ) {
        public ObserverSnapshot {
            if (queuedNewCount < 0 || completedTurnCount < 0) {
                throw new IllegalArgumentException(
                        "snapshot counters must be nonnegative"
                );
            }
            previousComments = List.copyOf(
                    Objects.requireNonNull(
                            previousComments,
                            "previousComments"
                    )
            );
        }
    }

    public record ObserverShutdownReport(
            int discardedQueuedObservations,
            int completedTurnCount
    ) {
    }

    private record QueuedProjection(
            ProjectedObservation observation,
            long queuedAtNanos
    ) {
        private QueuedProjection {
            observation = Objects.requireNonNull(
                    observation,
                    "observation"
            );
        }
    }

    private record ActiveTurn(
            long turnSequence,
            List<ProjectedObservation> triggers,
            List<DeliveredModelComment> previousComments,
            LlmDecisionRequestCompactor.Result.Fitted prepared,
            ModelInput exactInput
    ) {
        private ActiveTurn {
            if (turnSequence < 1) {
                throw new IllegalArgumentException(
                        "turnSequence must be positive"
                );
            }
            triggers = List.copyOf(
                    Objects.requireNonNull(triggers, "triggers")
            );
            previousComments = List.copyOf(Objects.requireNonNull(
                    previousComments,
                    "previousComments"
            ));
            if (triggers.isEmpty()) {
                throw new IllegalArgumentException(
                        "active turn requires triggers"
                );
            }
            if ((prepared == null) != (exactInput == null)) {
                throw new IllegalArgumentException(
                        "prepared turn and model input must appear together"
                );
            }
        }

        private static ActiveTurn preparing(
                long turnSequence,
                List<ProjectedObservation> triggers,
                List<DeliveredModelComment> comments
        ) {
            return new ActiveTurn(
                    turnSequence,
                    triggers,
                    comments,
                    null,
                    null
            );
        }

        /**
         * What the response may cite, taken from the request actually sent.
         *
         * <p>Only current-turn {@code NEW} triggers have a local id at all. A
         * hidden observation that changed state, a context fact and a previous
         * comment's evidence are outside it by construction rather than by a
         * rule someone has to remember to apply.</p>
         */
        private DecisionEvidence evidence() {
            return prepared.evidence();
        }

        private ActiveTurn prepared(
                LlmDecisionRequestCompactor.Result.Fitted value,
                ModelInput input
        ) {
            return new ActiveTurn(
                    turnSequence,
                    triggers,
                    previousComments,
                    value,
                    input
            );
        }

        private List<Long> triggerBusSequences() {
            return triggers.stream()
                    .map(ProjectedObservation::busSequence)
                    .toList();
        }
    }
}

sealed interface ObserverCommand {

    record QueueNewObservation(
            ProjectedObservation observation
    ) implements ObserverCommand {
        public QueueNewObservation {
            Objects.requireNonNull(observation, "observation");
            if (!(observation.trigger().payload()
                    instanceof JournalEventObservation)) {
                throw new IllegalArgumentException(
                        "NEW observation must contain a journal event"
                );
            }
        }
    }

    /**
     * Records the semantic effects of an observation that does not start a
     * turn.
     *
     * <p>Carries {@code CONTEXT_ONLY}, {@code STATUS} and {@code CONTROL}
     * effects, whose provenance is discarded today. Event selection is
     * unaffected: this command never makes an observation a trigger.</p>
     */
    record RecordSemanticEffect(
            SemanticObservationEnvelope envelope
    ) implements ObserverCommand {
        public RecordSemanticEffect {
            Objects.requireNonNull(envelope, "envelope");
        }
    }

    record ReplaySourceExhausted(
            PublishedObservation<ObservationSourceSignal> signal
    ) implements ObserverCommand {
        public ReplaySourceExhausted {
            Objects.requireNonNull(signal, "signal");
        }
    }
}
