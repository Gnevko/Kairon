package kairon.diagnostics;

import kairon.observation.ObservationPayload;
import kairon.observation.PublishedObservation;
import kairon.observation.bus.ObservationBus;
import kairon.observation.bus.ObservationBus.ObservationSubscription;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.status.StatusSnapshotObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * A bounded transport diagnostic reaction. It has no influence on observer
 * semantics or source publication.
 */
public final class TelemetryDiagnosticSubscriber {

    public static final String SUBSCRIBER_ID = "telemetry-diagnostic";
    private static final Logger LOGGER =
            LoggerFactory.getLogger(TelemetryDiagnosticSubscriber.class);

    public ObservationSubscription subscribeTo(ObservationBus bus) {
        Objects.requireNonNull(bus, "bus");
        return bus.subscribe(
                SUBSCRIBER_ID,
                ObservationPayload.class,
                this::onObservation
        );
    }

    public void onObservation(PublishedObservation<ObservationPayload> observation) {
        Objects.requireNonNull(observation, "observation");
        String optionalEventType = switch (observation.payload()) {
            case JournalEventObservation journal ->
                    journal.raw().optionalEventType().orElse(null);
            case StatusSnapshotObservation ignored -> "Status";
            default -> null;
        };
        LOGGER.debug(
                "OBSERVATION_DISPATCHED observationId={} busSequence={} source={} "
                        + "sourcePosition={} captureMode={} payloadType={} eventType={}",
                observation.observationId(),
                observation.busSequence(),
                observation.source(),
                observation.sourcePosition(),
                observation.captureMode(),
                observation.payload().getClass().getSimpleName(),
                optionalEventType
        );
    }
}
