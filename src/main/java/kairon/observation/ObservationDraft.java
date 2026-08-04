package kairon.observation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Source-owned observation metadata before process-local bus sequencing.
 */
public record ObservationDraft<T extends ObservationPayload>(
        String observationId,
        ObservationSource source,
        SourcePosition sourcePosition,
        Optional<Instant> sourceTime,
        Instant observedAt,
        ObservationCaptureMode captureMode,
        String schemaVersion,
        T payload
) {

    public ObservationDraft {
        observationId = requireNonBlank(observationId, "observationId");
        source = Objects.requireNonNull(source, "source");
        sourcePosition = Objects.requireNonNull(sourcePosition, "sourcePosition");
        sourceTime = Objects.requireNonNull(sourceTime, "sourceTime");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        captureMode = Objects.requireNonNull(captureMode, "captureMode");
        schemaVersion = requireNonBlank(schemaVersion, "schemaVersion");
        payload = Objects.requireNonNull(payload, "payload");
    }

    public record ObservationSource(
            String sourceType,
            String sourceInstanceId
    ) {

        public ObservationSource {
            sourceType = requireNonBlank(sourceType, "sourceType");
            sourceInstanceId = requireNonBlank(sourceInstanceId, "sourceInstanceId");
        }
    }

    /**
     * Marker for immutable, source-specific positions.
     */
    public interface SourcePosition {
    }

    public enum ObservationCaptureMode {
        BOOTSTRAP,
        LIVE,
        REPLAY
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
