package kairon.behavior.event;

@FunctionalInterface
public interface BehaviorGraphListener {

    BehaviorGraphListener NOOP = event -> {
    };

    void onBehaviorGraphEvent(BehaviorGraphEvent event);
}
