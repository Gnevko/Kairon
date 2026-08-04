package kairon.observation.status;

import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.ObservationDraft.SourcePosition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * Adds stable Status source identity and publication metadata without
 * interpreting state changes.
 */
public final class StatusObservationAdapter {

    private static final byte[] ID_DOMAIN =
            "kairon-status-snapshot-v1\0".getBytes(StandardCharsets.UTF_8);

    private final ObservationSource source;
    private final String statusBasename;

    public StatusObservationAdapter(
            ObservationSource source,
            String statusBasename
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.statusBasename = requireBasename(statusBasename);
    }

    public ObservationSource source() {
        return source;
    }

    public String statusBasename() {
        return statusBasename;
    }

    public ObservationDraft<StatusSnapshotObservation> adapt(
            StatusSnapshotObservation snapshot,
            long snapshotSequence,
            ObservationCaptureMode captureMode,
            Instant observedAt
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshotSequence < 0) {
            throw new IllegalArgumentException(
                    "snapshotSequence must be nonnegative"
            );
        }
        Objects.requireNonNull(captureMode, "captureMode");
        Objects.requireNonNull(observedAt, "observedAt");

        return new ObservationDraft<>(
                statusObservationId(statusBasename, snapshot),
                source,
                new StatusSourcePosition(statusBasename, snapshotSequence),
                snapshot.optionalTimestamp(),
                observedAt,
                captureMode,
                StatusSnapshotObservation.SCHEMA_VERSION,
                snapshot
        );
    }

    public static String statusObservationId(
            String statusBasename,
            StatusSnapshotObservation snapshot
    ) {
        String basename = requireBasename(statusBasename);
        Objects.requireNonNull(snapshot, "snapshot");
        MessageDigest digest = sha256();
        digest.update(ID_DOMAIN);
        update(digest, basename);
        update(
                digest,
                snapshot.optionalTimestamp()
                        .map(Instant::toString)
                        .orElse("<missing>")
        );
        update(digest, snapshot.rawJson());
        return "st1-" + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is required by Java",
                    exception
            );
        }
    }

    private static String requireBasename(String value) {
        Objects.requireNonNull(value, "statusBasename");
        if (value.isBlank()
                || value.contains("/")
                || value.contains("\\")) {
            throw new IllegalArgumentException(
                    "statusBasename must be a nonblank basename"
            );
        }
        return value;
    }

    /**
     * Stable position within one live Status source session. The sequence is
     * assigned only after the preceding changed snapshot was accepted.
     */
    public record StatusSourcePosition(
            String statusBasename,
            long snapshotSequence
    ) implements SourcePosition {

        public StatusSourcePosition {
            statusBasename = requireBasename(statusBasename);
            if (snapshotSequence < 0) {
                throw new IllegalArgumentException(
                        "snapshotSequence must be nonnegative"
                );
            }
        }
    }
}
