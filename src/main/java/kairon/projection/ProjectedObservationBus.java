package kairon.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registration-ordered synchronous publisher for completed projections.
 */
public final class ProjectedObservationBus implements AutoCloseable {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProjectedObservationBus.class);

    private final Object registrationGate = new Object();
    private final CopyOnWriteArrayList<BusSubscription> subscriptions =
            new CopyOnWriteArrayList<>();
    private final Set<String> lifetimeSubscriberIds = new HashSet<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public Subscription subscribe(
            String subscriberId,
            Handler handler
    ) {
        requireNonBlank(subscriberId, "subscriberId");
        Objects.requireNonNull(handler, "handler");
        synchronized (registrationGate) {
            if (closed.get()) {
                throw new IllegalStateException(
                        "ProjectedObservationBus is closed"
                );
            }
            if (!lifetimeSubscriberIds.add(subscriberId)) {
                throw new IllegalArgumentException(
                        "subscriberId has already been used: " + subscriberId
                );
            }
            BusSubscription subscription = new BusSubscription(
                    this,
                    subscriberId,
                    handler
            );
            subscriptions.add(subscription);
            return subscription;
        }
    }

    public void publish(ProjectedObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (closed.get()) {
            throw new IllegalStateException(
                    "ProjectedObservationBus is closed"
            );
        }
        for (BusSubscription subscription : subscriptions) {
            if (!subscription.active.get()) {
                continue;
            }
            try {
                subscription.handler.onProjectedObservation(observation);
            } catch (RuntimeException failure) {
                LOGGER.warn(
                        "PROJECTED_OBSERVATION_HANDLER_FAILED "
                                + "subscriberId={} observationId={} "
                                + "busSequence={}",
                        subscription.subscriberId,
                        observation.trigger().observationId(),
                        observation.busSequence(),
                        failure
                );
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (BusSubscription subscription : subscriptions) {
            subscription.active.set(false);
        }
        subscriptions.clear();
    }

    private void closeSubscription(BusSubscription subscription) {
        if (subscription.active.compareAndSet(true, false)) {
            subscriptions.remove(subscription);
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @FunctionalInterface
    public interface Handler {

        void onProjectedObservation(ProjectedObservation observation);
    }

    public interface Subscription extends AutoCloseable {

        String subscriberId();

        boolean isActive();

        @Override
        void close();
    }

    private static final class BusSubscription implements Subscription {

        private final ProjectedObservationBus owner;
        private final String subscriberId;
        private final Handler handler;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private BusSubscription(
                ProjectedObservationBus owner,
                String subscriberId,
                Handler handler
        ) {
            this.owner = owner;
            this.subscriberId = subscriberId;
            this.handler = handler;
        }

        @Override
        public String subscriberId() {
            return subscriberId;
        }

        @Override
        public boolean isActive() {
            return active.get() && !owner.closed.get();
        }

        @Override
        public void close() {
            owner.closeSubscription(this);
        }
    }
}
