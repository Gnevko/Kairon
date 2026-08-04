package kairon.observation;

import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.ObservationDraft.SourcePosition;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable observation delivered by the bus with process-local sequencing.
 */
public record PublishedObservation<T extends ObservationPayload>(
        String observationId,
        long busSequence,
        ObservationSource source,
        SourcePosition sourcePosition,
        Optional<Instant> sourceTime,
        Instant observedAt,
        ObservationCaptureMode captureMode,
        String schemaVersion,
        T payload
) {

    public PublishedObservation {
        observationId = requireNonBlank(observationId, "observationId");
        if (busSequence < 1) {
            throw new IllegalArgumentException("busSequence must be positive");
        }
        source = Objects.requireNonNull(source, "source");
        sourcePosition = Objects.requireNonNull(sourcePosition, "sourcePosition");
        sourceTime = Objects.requireNonNull(sourceTime, "sourceTime");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        captureMode = Objects.requireNonNull(captureMode, "captureMode");
        schemaVersion = requireNonBlank(schemaVersion, "schemaVersion");
        payload = Objects.requireNonNull(payload, "payload");
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
