package kairon.projection;

import kairon.behavior.context.BodyDetailLookup;
import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.graph.BehaviorGraphApplyStatus;
import kairon.behavior.graph.BehaviorGraphProcessor;
import kairon.behavior.snapshot.BehaviorSituationCaptureStatus;
import kairon.behavior.snapshot.BehaviorSituationInconsistencyException;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.behavior.snapshot.BehaviorSituationSnapshotProvider;
import kairon.observation.PublishedObservation;
import kairon.semantics.SemanticObservationEnvelope;
import kairon.state.CurrentGameStateProjection;
import kairon.state.CurrentGameStateProjectionWriter;
import kairon.state.CurrentGameStateSnapshot;
import kairon.system.CurrentSystemRegistry;
import kairon.system.SystemRegistrySnapshot;
import kairon.system.VisitIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single FIFO boundary from raw observations to post-projection envelopes.
 */
public final class ObservationProjectionCoordinator
        implements AutoCloseable {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ObservationProjectionCoordinator.class);

    private final Object gate = new Object();
    private final CurrentGameStateProjectionWriter stateProjector;
    private final BehaviorGraphProcessor graphProcessor;
    private final BehaviorSituationSnapshotProvider situationProvider;
    private final ProjectedObservationBus downstream;
    private final SemanticEnvelopeFactory semanticEnvelopeFactory =
            SemanticEnvelopeFactory.production();

    /**
     * The current system, built here rather than supplied.
     *
     * <p>Unlike the behaviour graph it is not configurable, has no store and
     * reaches nothing outside itself, so there is nothing for a caller to
     * choose. It is owned like the envelope factory above and written on this
     * coordinator's single thread.</p>
     */
    private final CurrentSystemRegistry systemRegistry =
            new CurrentSystemRegistry();
    private final ExecutorService executor;
    private final AtomicReference<Throwable> stateFailure =
            new AtomicReference<>();

    private boolean accepting = true;
    private long lastBusSequence;
    private CompletableFuture<Void> shutdown;

    public ObservationProjectionCoordinator(
            CurrentGameStateProjectionWriter stateProjector,
            Optional<? extends BehaviorGraphProcessor> graphProcessor,
            Optional<? extends BehaviorSituationSnapshotProvider>
                    situationProvider,
            ProjectedObservationBus downstream
    ) {
        this.stateProjector = Objects.requireNonNull(
                stateProjector,
                "stateProjector"
        );
        this.graphProcessor = Objects.requireNonNull(
                graphProcessor,
                "graphProcessor"
        ).orElse(null);
        this.situationProvider = Objects.requireNonNull(
                situationProvider,
                "situationProvider"
        ).orElse(null);
        if ((this.graphProcessor == null)
                != (this.situationProvider == null)) {
            throw new IllegalArgumentException(
                    "graph processor and situation provider "
                            + "must be configured together"
            );
        }
        this.downstream = Objects.requireNonNull(
                downstream,
                "downstream"
        );
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "observation-projection");
            thread.setDaemon(false);
            return thread;
        });
    }

    public CompletionStage<Void> submit(
            PublishedObservation<?> observation
    ) {
        Objects.requireNonNull(observation, "observation");
        CompletableFuture<Void> completion = new CompletableFuture<>();
        synchronized (gate) {
            if (!accepting) {
                throw new IllegalStateException(
                        "ObservationProjectionCoordinator is closed"
                );
            }
            try {
                executor.execute(
                        () -> project(observation, completion)
                );
            } catch (RejectedExecutionException failure) {
                throw failure;
            }
        }
        return completion;
    }

    /**
     * Completes after every observation accepted before this call reaches a
     * terminal projection outcome and downstream publication, if applicable.
     */
    public CompletionStage<Void> awaitIdle() {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        synchronized (gate) {
            if (!accepting) {
                return shutdown == null
                        ? CompletableFuture.failedStage(
                                new IllegalStateException(
                                        "ObservationProjectionCoordinator "
                                                + "is closed"
                                )
                        )
                        : shutdown;
            }
            try {
                executor.execute(() -> completeBarrier(barrier));
            } catch (RejectedExecutionException failure) {
                barrier.completeExceptionally(failure);
            }
        }
        return barrier;
    }

    public CompletionStage<Void> shutdown() {
        synchronized (gate) {
            if (shutdown != null) {
                return shutdown;
            }
            accepting = false;
            shutdown = new CompletableFuture<>();
            try {
                executor.execute(this::finishShutdown);
            } catch (RejectedExecutionException failure) {
                shutdown.completeExceptionally(failure);
                executor.shutdown();
            }
            return shutdown;
        }
    }

    @Override
    public void close() {
        shutdown().toCompletableFuture().join();
    }

    private void project(
            PublishedObservation<?> observation,
            CompletableFuture<Void> completion
    ) {
        try {
            requireIncreasingBusSequence(observation);
            CurrentGameStateProjection stateProjection =
                    stateProjector.applyAndCapture(observation);
            if (stateProjection.busSequence()
                    != observation.busSequence()) {
                throw new IllegalStateException(
                        "state projection returned a different busSequence"
                );
            }

            SystemRegistrySnapshot registrySnapshot =
                    applyRegistry(observation, stateProjection);

            BehaviorGraphApplyResult graphResult = applyGraph(
                    observation,
                    stateProjection,
                    new RegistryBodyDetail(registrySnapshot)
            );
            if (graphResult.busSequence()
                    != observation.busSequence()) {
                throw new IllegalStateException(
                        "graph projection returned a different busSequence"
                );
            }
            BehaviorSituationSnapshot behaviorSituation =
                    captureBehaviorSituation(
                            observation,
                            stateProjection.currentState(),
                            graphResult
                    );
            SemanticObservationEnvelope semanticEnvelope =
                    semanticEnvelopeFactory.create(
                            observation,
                            stateProjection.applied()
                    );
            downstream.publish(new ProjectedObservation(
                    observation,
                    stateProjection.applied(),
                    stateProjection.changes(),
                    graphResult,
                    behaviorSituation,
                    semanticEnvelope,
                    registrySnapshot
            ));
            completion.complete(null);
        } catch (RuntimeException stateOrBoundaryFailure) {
            stateFailure.compareAndSet(null, stateOrBoundaryFailure);
            LOGGER.error(
                    "CURRENT_STATE_PROJECTION_FAILED observationId={} "
                            + "busSequence={} category={}",
                    observation.observationId(),
                    observation.busSequence(),
                    stateOrBoundaryFailure.getClass().getSimpleName(),
                    stateOrBoundaryFailure
            );
            completion.completeExceptionally(stateOrBoundaryFailure);
        }
    }

    /**
     * The current system, updated and captured before the graph runs.
     *
     * <p>The order is fixed for reproducibility and carries no dependency:
     * neither projection reads the other, and two peer projections that read
     * each other are two projections that drift.</p>
     *
     * <p>A failure here is isolated exactly as a graph failure is. The registry
     * is pure computation over the record, so a failure is a defect rather than
     * an environmental fault — but FIFO processing must not stop for a defect
     * either, and a reader has to be able to tell an empty system from a
     * registry that could not answer.</p>
     */
    private SystemRegistrySnapshot applyRegistry(
            PublishedObservation<?> observation,
            CurrentGameStateProjection stateProjection
    ) {
        CurrentGameStateSnapshot currentState = stateProjection.currentState();
        try {
            return systemRegistry.applyAndCapture(
                    observation,
                    new VisitIdentity(
                            currentState.commanderFid(),
                            currentState.shipId(),
                            currentState.systemAddress(),
                            currentState.systemName()
                    )
            );
        } catch (RuntimeException registryFailure) {
            LOGGER.error(
                    "SYSTEM_REGISTRY_PROCESSING_FAILED observationId={} "
                            + "busSequence={} category={}",
                    observation.observationId(),
                    observation.busSequence(),
                    registryFailure.getClass().getSimpleName(),
                    registryFailure
            );
            return SystemRegistrySnapshot.unavailable(
                    observation.busSequence()
            );
        }
    }

    /**
     * The graph, applied to the observation with the system it happened in.
     *
     * <p>Body detail is handed in rather than looked up, and it is the snapshot
     * this observation produced a moment ago — the registry has been updated
     * for this record and has not moved on. That is what keeps the order above
     * a sequence rather than a dependency: the graph receives plain values and
     * never learns what produced them.</p>
     */
    private BehaviorGraphApplyResult applyGraph(
            PublishedObservation<?> observation,
            CurrentGameStateProjection stateProjection,
            BodyDetailLookup bodies
    ) {
        if (graphProcessor == null) {
            return BehaviorGraphApplyResult.disabled(
                    observation.busSequence()
            );
        }
        try {
            return graphProcessor.apply(observation, stateProjection, bodies);
        } catch (RuntimeException graphFailure) {
            LOGGER.error(
                    "BEHAVIOR_GRAPH_PROCESSING_FAILED observationId={} "
                            + "busSequence={} category={}",
                    observation.observationId(),
                    observation.busSequence(),
                    graphFailure.getClass().getSimpleName(),
                    graphFailure
            );
            return BehaviorGraphApplyResult.failed(
                    observation.busSequence()
            );
        }
    }

    private BehaviorSituationSnapshot captureBehaviorSituation(
            PublishedObservation<?> observation,
            CurrentGameStateSnapshot currentState,
            BehaviorGraphApplyResult graphResult
    ) {
        if (graphResult.status()
                == BehaviorGraphApplyStatus.DISABLED) {
            return BehaviorSituationSnapshot.unavailable(
                    graphResult,
                    BehaviorSituationCaptureStatus.GRAPH_DISABLED
            );
        }
        if (graphResult.status()
                == BehaviorGraphApplyStatus.FAILED) {
            return BehaviorSituationSnapshot.unavailable(
                    graphResult,
                    BehaviorSituationCaptureStatus.GRAPH_APPLY_FAILED
            );
        }
        try {
            BehaviorSituationSnapshot captured =
                    situationProvider.capture(
                            observation,
                            currentState,
                            graphResult
                    );
            if (!captured.applyResult().equals(graphResult)) {
                throw new BehaviorSituationInconsistencyException(
                        "capture returned different graph apply metadata"
                );
            }
            return captured;
        } catch (BehaviorSituationInconsistencyException inconsistency) {
            LOGGER.error(
                    "BEHAVIOR_SITUATION_INCONSISTENT observationId={} "
                            + "busSequence={} graphId={} message={}",
                    observation.observationId(),
                    observation.busSequence(),
                    graphResult.activeGraphId()
                            .map(graphId -> graphId.canonicalValue())
                            .orElse("<none>"),
                    inconsistency.getMessage(),
                    inconsistency
            );
            return BehaviorSituationSnapshot.unavailable(
                    graphResult,
                    BehaviorSituationCaptureStatus.INCONSISTENT
            );
        } catch (RuntimeException captureFailure) {
            LOGGER.error(
                    "BEHAVIOR_SITUATION_CAPTURE_FAILED observationId={} "
                            + "busSequence={} graphId={} category={}",
                    observation.observationId(),
                    observation.busSequence(),
                    graphResult.activeGraphId()
                            .map(graphId -> graphId.canonicalValue())
                            .orElse("<none>"),
                    captureFailure.getClass().getSimpleName(),
                    captureFailure
            );
            return BehaviorSituationSnapshot.unavailable(
                    graphResult,
                    BehaviorSituationCaptureStatus.SNAPSHOT_FAILED
            );
        }
    }

    private void requireIncreasingBusSequence(
            PublishedObservation<?> observation
    ) {
        if (observation.busSequence() <= lastBusSequence) {
            throw new IllegalStateException(
                    "projection observations are out of bus order: "
                            + observation.busSequence()
                            + " after "
                            + lastBusSequence
            );
        }
        lastBusSequence = observation.busSequence();
    }

    private void completeBarrier(CompletableFuture<Void> barrier) {
        Throwable failure = stateFailure.get();
        if (failure == null) {
            barrier.complete(null);
        } else {
            barrier.completeExceptionally(failure);
        }
    }

    private void finishShutdown() {
        Throwable failure = stateFailure.get();
        try {
            if (graphProcessor != null) {
                graphProcessor.close();
            }
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else if (failure != closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        try {
            downstream.close();
        } finally {
            executor.shutdown();
        }
        if (failure == null) {
            shutdown.complete(null);
        } else {
            shutdown.completeExceptionally(failure);
        }
    }
}
