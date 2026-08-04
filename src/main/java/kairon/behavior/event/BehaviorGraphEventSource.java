package kairon.behavior.event;

/**
 * Lifecycle-aware source of internal behavior graph notifications.
 */
public interface BehaviorGraphEventSource {

    Subscription subscribe(BehaviorGraphListener listener);

    interface Subscription extends AutoCloseable {

        boolean isActive();

        @Override
        void close();
    }
}
