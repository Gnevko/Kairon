package kairon.projection;

import kairon.observation.ObservationPayload;
import kairon.observation.PublishedObservation;
import kairon.observation.bus.ObservationBus;
import kairon.observation.bus.ObservationBus.ObservationSubscription;

import java.util.Objects;

/**
 * The only raw-bus handoff into state and graph projection.
 */
public final class ObservationProjectionSubscriber {

    public static final String SUBSCRIBER_ID =
            "observation-projection";

    private final ObservationProjectionCoordinator coordinator;

    public ObservationProjectionSubscriber(
            ObservationProjectionCoordinator coordinator
    ) {
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator"
        );
    }

    public Subscription subscribeTo(ObservationBus bus) {
        Objects.requireNonNull(bus, "bus");
        return new Subscription(bus.subscribe(
                SUBSCRIBER_ID,
                ObservationPayload.class,
                this::onObservation
        ));
    }

    private void onObservation(
            PublishedObservation<ObservationPayload> observation
    ) {
        coordinator.submit(observation);
    }

    public record Subscription(
            ObservationSubscription handoff
    ) implements AutoCloseable {

        public Subscription {
            Objects.requireNonNull(handoff, "handoff");
        }

        public boolean isActive() {
            return handoff.isActive();
        }

        public boolean ownsSubscriberId(String subscriberId) {
            return handoff.subscriberId().equals(
                    Objects.requireNonNull(subscriberId, "subscriberId")
            );
        }

        @Override
        public void close() {
            handoff.close();
        }
    }
}
