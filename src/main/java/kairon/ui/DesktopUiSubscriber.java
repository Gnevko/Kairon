package kairon.ui;

import kairon.observation.PublishedObservation;
import kairon.observation.bus.ObservationBus;
import kairon.observation.bus.ObservationBus.ObservationSubscription;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalObservationAdapter.JournalSourcePosition;

import java.util.Objects;

/**
 * ObservationBus-to-GUI bridge for every valid journal observation.
 *
 * <p>It does not reuse the LLM event selection and performs no semantic
 * filtering.</p>
 */
public final class DesktopUiSubscriber {

    public static final String SUBSCRIBER_ID = "desktop-ui-journal-events";

    private final KaironGuiHub guiHub;

    public DesktopUiSubscriber(KaironGuiHub guiHub) {
        this.guiHub = Objects.requireNonNull(guiHub, "guiHub");
    }

    public ObservationSubscription subscribeTo(ObservationBus bus) {
        Objects.requireNonNull(bus, "bus");
        return bus.subscribe(
                SUBSCRIBER_ID,
                JournalEventObservation.class,
                this::onObservation
        );
    }

    private void onObservation(
            PublishedObservation<JournalEventObservation> observation
    ) {
        Objects.requireNonNull(observation, "observation");
        JournalEventObservation payload = observation.payload();
        String eventType = payload.raw().optionalEventType()
                .orElse(payload.getClass().getSimpleName());
        String sourcePosition = observation.sourcePosition().toString();
        if (observation.sourcePosition()
                instanceof JournalSourcePosition position) {
            sourcePosition = position.journalBasename()
                    + ':'
                    + position.zeroBasedSourceByteOffset();
        }

        guiHub.postObservation(new KaironGuiHub.ObservationView(
                observation.observationId(),
                observation.busSequence(),
                observation.observedAt(),
                observation.sourceTime(),
                observation.source().sourceType()
                        + '/'
                        + observation.source().sourceInstanceId(),
                sourcePosition,
                observation.captureMode().name(),
                eventType,
                payload.getClass().getName(),
                payload.raw().rawJson()
        ));
    }
}
