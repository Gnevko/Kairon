package kairon.observation.bus;

import kairon.observation.ObservationDraft;
import kairon.observation.ObservationPayload;
import kairon.observation.PublishedObservation;
import kairon.observation.bus.ObservationBus.ObservationHandler;
import kairon.observation.bus.ObservationBus.ObservationSubscription;
import kairon.observation.bus.ObservationBus.PublishReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Phase 0 bus: one FIFO executor invokes matching handoff-only handlers
 * directly in registration order.
 */
public final class InProcessObservationBus implements ObservationBus {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(InProcessObservationBus.class);

    private final Object gate = new Object();
    private final ExecutorService executor;
    private final List<BusSubscription<?>> subscriptions = new ArrayList<>();
    private final Set<String> lifetimeSubscriberIds = new HashSet<>();
    private final Set<CompletableFuture<?>> pendingStages = new LinkedHashSet<>();
    private final CompletableFuture<Void> terminalStage = new CompletableFuture<>();

    private volatile BusState state = BusState.RUNNING;
    private volatile Thread busThread;
    private RejectedExecutionException executorFailure;
    private long nextBusSequence = 1;

    public InProcessObservationBus() {
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "observation-bus");
            thread.setDaemon(false);
            busThread = thread;
            return thread;
        });
    }

    @Override
    public <T extends ObservationPayload> ObservationSubscription subscribe(
            String subscriberId,
            Class<T> payloadType,
            ObservationHandler<T> handler
    ) {
        requireNonBlank(subscriberId, "subscriberId");
        Objects.requireNonNull(payloadType, "payloadType");
        Objects.requireNonNull(handler, "handler");
        rejectControlFromBusThread("subscribe");

        CompletableFuture<BusSubscription<T>> registration = new CompletableFuture<>();
        synchronized (gate) {
            requireRunningForControl();
            pendingStages.add(registration);
            try {
                executor.execute(
                        () -> registerOnBus(
                                subscriberId,
                                payloadType,
                                handler,
                                registration
                        )
                );
            } catch (RejectedExecutionException rejection) {
                failFromExecutorRejectionLocked(
                        "subscription-registration",
                        rejection
                );
                throw rejection;
            }
        }
        return joinAndRethrow(registration);
    }

    @Override
    public <T extends ObservationPayload> CompletionStage<PublishReceipt> publish(
            ObservationDraft<T> observation
    ) {
        Objects.requireNonNull(observation, "observation");
        ObservationDraft<T> snapshot = copyDraft(observation);
        CompletableFuture<PublishReceipt> receipt = new CompletableFuture<>();

        synchronized (gate) {
            if (state == BusState.FAILED) {
                receipt.completeExceptionally(executorFailure);
                return receipt;
            }
            if (state != BusState.RUNNING) {
                receipt.completeExceptionally(
                        new IllegalStateException("ObservationBus is not running")
                );
                return receipt;
            }

            pendingStages.add(receipt);
            try {
                executor.execute(() -> dispatchOnBus(snapshot, receipt));
            } catch (RejectedExecutionException rejection) {
                failFromExecutorRejectionLocked("publication", rejection);
            }
        }
        return receipt;
    }

    @Override
    public CompletionStage<Void> drainAndClose() {
        rejectControlFromBusThread("drainAndClose");
        synchronized (gate) {
            if (state != BusState.RUNNING) {
                return terminalStage;
            }
            state = BusState.DRAINING;
            try {
                executor.execute(this::finishDrainOnBus);
            } catch (RejectedExecutionException rejection) {
                failFromExecutorRejectionLocked("drain", rejection);
            }
            return terminalStage;
        }
    }

    @Override
    public void close() {
        rejectControlFromBusThread("close");
        try {
            drainAndClose().toCompletableFuture().join();
        } catch (CompletionException failure) {
            throw new IllegalStateException(
                    "ObservationBus did not drain normally",
                    failure.getCause()
            );
        }
    }

    private <T extends ObservationPayload> void registerOnBus(
            String subscriberId,
            Class<T> payloadType,
            ObservationHandler<T> handler,
            CompletableFuture<BusSubscription<T>> registration
    ) {
        if (state == BusState.FAILED) {
            return;
        }
        if (lifetimeSubscriberIds.contains(subscriberId)) {
            completePendingExceptionally(
                    registration,
                    new IllegalArgumentException(
                            "subscriberId has already been used: " + subscriberId
                    )
            );
            return;
        }

        BusSubscription<T> subscription =
                new BusSubscription<>(this, subscriberId, payloadType, handler);
        lifetimeSubscriberIds.add(subscriberId);
        subscriptions.add(subscription);
        completePending(registration, subscription);
    }

    private <T extends ObservationPayload> void dispatchOnBus(
            ObservationDraft<T> draft,
            CompletableFuture<PublishReceipt> receipt
    ) {
        if (state == BusState.FAILED) {
            return;
        }

        long busSequence = nextBusSequence++;
        PublishedObservation<T> published = new PublishedObservation<>(
                draft.observationId(),
                busSequence,
                draft.source(),
                draft.sourcePosition(),
                draft.sourceTime(),
                draft.observedAt(),
                draft.captureMode(),
                draft.schemaVersion(),
                draft.payload()
        );
        List<String> matchedSubscriberIds = new ArrayList<>();
        List<String> failedSubscriberIds = new ArrayList<>();
        Class<?> actualPayloadClass = draft.payload().getClass();

        for (BusSubscription<?> subscription : subscriptions) {
            if (state == BusState.FAILED) {
                return;
            }
            if (!subscription.active
                    || !subscription.payloadType.isAssignableFrom(actualPayloadClass)) {
                continue;
            }

            matchedSubscriberIds.add(subscription.subscriberId);
            try {
                invoke(subscription, published);
            } catch (Exception handlerFailure) {
                failedSubscriberIds.add(subscription.subscriberId);
                LOGGER.warn(
                        "OBSERVATION_HANDLER_FAILED subscriberId={} "
                                + "observationId={} busSequence={}",
                        subscription.subscriberId,
                        published.observationId(),
                        published.busSequence(),
                        handlerFailure
                );
            }
        }

        completePending(
                receipt,
                new PublishReceipt(
                        published.observationId(),
                        published.busSequence(),
                        matchedSubscriberIds,
                        failedSubscriberIds
                )
        );
    }

    @SuppressWarnings("unchecked")
    private static <T extends ObservationPayload> void invoke(
            BusSubscription<?> subscription,
            PublishedObservation<T> observation
    ) {
        BusSubscription<T> typedSubscription = (BusSubscription<T>) subscription;
        typedSubscription.handler.onObservation(observation);
    }

    private void closeSubscription(BusSubscription<?> subscription) {
        rejectControlFromBusThread("subscription close");

        CompletableFuture<Void> waitFor;
        boolean waitingForDrain;
        synchronized (gate) {
            if (state == BusState.FAILED || state == BusState.TERMINATED) {
                subscription.active = false;
                return;
            }
            if (!subscription.active) {
                return;
            }
            if (state == BusState.DRAINING) {
                waitFor = terminalStage;
                waitingForDrain = true;
            } else if (subscription.closeRequested) {
                waitFor = subscription.closeStage;
                waitingForDrain = false;
            } else {
                subscription.closeRequested = true;
                waitFor = subscription.closeStage;
                waitingForDrain = false;
                pendingStages.add(waitFor);
                try {
                    executor.execute(() -> closeSubscriptionOnBus(subscription));
                } catch (RejectedExecutionException rejection) {
                    failFromExecutorRejectionLocked(
                            "subscription-closure",
                            rejection
                    );
                    throw rejection;
                }
            }
        }

        try {
            joinAndRethrow(waitFor);
        } catch (RejectedExecutionException rejection) {
            if (!waitingForDrain) {
                throw rejection;
            }
            throw new IllegalStateException(
                    "ObservationBus drain failed while closing subscription",
                    rejection
            );
        } catch (RuntimeException failure) {
            if (!waitingForDrain) {
                throw failure;
            }
            throw new IllegalStateException(
                    "ObservationBus drain failed while closing subscription",
                    failure
            );
        }
    }

    private void closeSubscriptionOnBus(BusSubscription<?> subscription) {
        if (state == BusState.FAILED) {
            return;
        }
        subscription.active = false;
        completePending(subscription.closeStage, null);
    }

    private void finishDrainOnBus() {
        if (state == BusState.FAILED) {
            return;
        }
        for (BusSubscription<?> subscription : subscriptions) {
            subscription.active = false;
        }
        synchronized (gate) {
            if (state != BusState.DRAINING) {
                return;
            }
            state = BusState.TERMINATED;
        }
        executor.shutdown();
        terminalStage.complete(null);
    }

    private void requireRunningForControl() {
        if (state == BusState.FAILED) {
            throw executorFailure;
        }
        if (state != BusState.RUNNING) {
            throw new IllegalStateException("ObservationBus is not running");
        }
    }

    private boolean callbacksMayBegin() {
        BusState current = state;
        return current == BusState.RUNNING || current == BusState.DRAINING;
    }

    private void rejectControlFromBusThread(String operation) {
        if (Thread.currentThread() == busThread) {
            throw new IllegalStateException(
                    operation + " cannot run on observation-bus"
            );
        }
    }

    private void failFromExecutorRejectionLocked(
            String taskCategory,
            RejectedExecutionException rejection
    ) {
        if (state == BusState.FAILED) {
            return;
        }
        state = BusState.FAILED;
        executorFailure = rejection;

        List<CompletableFuture<?>> unresolved = List.copyOf(pendingStages);
        pendingStages.clear();
        for (CompletableFuture<?> stage : unresolved) {
            stage.completeExceptionally(rejection);
        }
        terminalStage.completeExceptionally(rejection);
        executor.shutdownNow();
        LOGGER.error(
                "OBSERVATION_BUS_EXECUTOR_REJECTED taskCategory={}",
                taskCategory,
                rejection
        );
    }

    private <T> void completePending(CompletableFuture<T> stage, T value) {
        synchronized (gate) {
            if (!stage.isDone()) {
                stage.complete(value);
            }
            pendingStages.remove(stage);
        }
    }

    private void completePendingExceptionally(
            CompletableFuture<?> stage,
            RuntimeException failure
    ) {
        synchronized (gate) {
            if (!stage.isDone()) {
                stage.completeExceptionally(failure);
            }
            pendingStages.remove(stage);
        }
    }

    private static <T extends ObservationPayload> ObservationDraft<T> copyDraft(
            ObservationDraft<T> draft
    ) {
        return new ObservationDraft<>(
                draft.observationId(),
                draft.source(),
                draft.sourcePosition(),
                draft.sourceTime(),
                draft.observedAt(),
                draft.captureMode(),
                draft.schemaVersion(),
                draft.payload()
        );
    }

    private static <T> T joinAndRethrow(CompletableFuture<T> stage) {
        try {
            return stage.join();
        } catch (CompletionException completionFailure) {
            Throwable cause = completionFailure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private enum BusState {
        RUNNING,
        DRAINING,
        TERMINATED,
        FAILED
    }

    private static final class BusSubscription<T extends ObservationPayload>
            implements ObservationSubscription {

        private final InProcessObservationBus owner;
        private final String subscriberId;
        private final Class<T> payloadType;
        private final ObservationHandler<T> handler;
        private final CompletableFuture<Void> closeStage = new CompletableFuture<>();

        private volatile boolean active = true;
        private boolean closeRequested;

        private BusSubscription(
                InProcessObservationBus owner,
                String subscriberId,
                Class<T> payloadType,
                ObservationHandler<T> handler
        ) {
            this.owner = owner;
            this.subscriberId = subscriberId;
            this.payloadType = payloadType;
            this.handler = handler;
        }

        @Override
        public String subscriberId() {
            return subscriberId;
        }

        @Override
        public boolean isActive() {
            return active && owner.callbacksMayBegin();
        }

        @Override
        public void close() {
            owner.closeSubscription(this);
        }
    }
}
