package kairon.behavior.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe fan-out adapter for the existing graph listener contract.
 */
public final class BehaviorGraphEventBus
        implements BehaviorGraphEventSource, BehaviorGraphListener,
        AutoCloseable {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(BehaviorGraphEventBus.class);

    private final CopyOnWriteArrayList<Registration> registrations =
            new CopyOnWriteArrayList<>();
    private final AtomicBoolean open = new AtomicBoolean(true);

    @Override
    public Subscription subscribe(BehaviorGraphListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (!open.get()) {
            throw new IllegalStateException(
                    "BehaviorGraphEventBus is closed"
            );
        }
        Registration registration = new Registration(listener);
        registrations.add(registration);
        if (!open.get()) {
            registration.close();
            throw new IllegalStateException(
                    "BehaviorGraphEventBus is closed"
            );
        }
        return registration;
    }

    @Override
    public void onBehaviorGraphEvent(BehaviorGraphEvent event) {
        Objects.requireNonNull(event, "event");
        for (Registration registration : registrations) {
            try {
                registration.dispatch(event);
            } catch (RuntimeException failure) {
                LOGGER.warn(
                        "BEHAVIOR_GRAPH_LISTENER_FAILED eventType={} "
                                + "graphId={} category={}",
                        event.getClass().getSimpleName(),
                        event.graphId().canonicalValue(),
                        failure.getClass().getSimpleName()
                );
            }
        }
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        for (Registration registration : registrations) {
            registration.close();
        }
        registrations.clear();
    }

    private final class Registration implements Subscription {

        private final BehaviorGraphListener listener;
        private boolean active = true;

        private Registration(BehaviorGraphListener listener) {
            this.listener = listener;
        }

        @Override
        public synchronized boolean isActive() {
            return active;
        }

        @Override
        public synchronized void close() {
            if (!active) {
                return;
            }
            active = false;
            registrations.remove(this);
        }

        private synchronized void dispatch(BehaviorGraphEvent event) {
            if (active) {
                listener.onBehaviorGraphEvent(event);
            }
        }
    }
}
