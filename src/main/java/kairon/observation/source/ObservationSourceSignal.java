package kairon.observation.source;

import kairon.observation.ObservationPayload;

import java.util.Objects;

/**
 * A technical source-state notification. It is never a game journal event.
 */
public record ObservationSourceSignal(
        ObservationSourceSignalType signalType
) implements ObservationPayload {

    public static final String SCHEMA_VERSION = "kairon.observation-source-signal/v1";

    public ObservationSourceSignal {
        signalType = Objects.requireNonNull(signalType, "signalType");
    }

    public enum ObservationSourceSignalType {
        REPLAY_SOURCE_EXHAUSTED
    }
}
