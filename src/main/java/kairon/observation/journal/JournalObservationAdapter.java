package kairon.observation.journal;

import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Adds source identity and immutable observation metadata without interpreting
 * journal semantics.
 */
public final class JournalObservationAdapter {

    private static final byte[] ID_DOMAIN =
            "kairon-journal-event-v1\0".getBytes(StandardCharsets.UTF_8);

    private final ObservationSource source;
    private final Map<String, Reservation> reservations = new HashMap<>();

    public JournalObservationAdapter(ObservationSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public ObservationSource source() {
        return source;
    }

    public synchronized ObservationDraft<JournalEventObservation> adapt(
            ParsedJournalRecord record,
            ObservationCaptureMode captureMode,
            Instant observedAt
    ) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(captureMode, "captureMode");
        Objects.requireNonNull(observedAt, "observedAt");

        String observationId = journalObservationId(
                record.journalBasename(),
                record.zeroBasedSourceByteOffset()
        );
        Reservation candidate = new Reservation(
                record.journalBasename(),
                record.zeroBasedSourceByteOffset(),
                fingerprint(record.rawJson()),
                ReservationState.PENDING
        );
        Reservation existing = reservations.get(observationId);
        if (existing != null) {
            if (existing.samePhysicalRecord(candidate)) {
                throw new ExactDuplicateJournalObservationException(observationId);
            }
            throw new ObservationIdentityCollisionException(observationId);
        }
        reservations.put(observationId, candidate);

        try {
            RawJournalData raw = new RawJournalData(
                    record.rawJson(),
                    record.parsedJsonObject(),
                    record.optionalEventType(),
                    record.optionalJournalTimestamp()
            );
            JournalEventObservation payload = JournalEventCatalog.create(raw);
            return new ObservationDraft<>(
                    observationId,
                    source,
                    new JournalSourcePosition(
                            record.journalBasename(),
                            record.zeroBasedSourceByteOffset()
                    ),
                    record.optionalJournalTimestamp(),
                    observedAt,
                    captureMode,
                    JournalEventObservation.SCHEMA_VERSION,
                    payload
            );
        } catch (RuntimeException constructionFailure) {
            reservations.remove(observationId);
            throw constructionFailure;
        }
    }

    public synchronized void commit(String observationId) {
        Objects.requireNonNull(observationId, "observationId");
        Reservation current = reservations.get(observationId);
        if (current == null) {
            throw new IllegalStateException("unknown observation reservation");
        }
        reservations.put(observationId, current.withState(ReservationState.COMMITTED));
    }

    public synchronized void rollback(String observationId) {
        Objects.requireNonNull(observationId, "observationId");
        Reservation current = reservations.get(observationId);
        if (current != null && current.state() == ReservationState.PENDING) {
            reservations.remove(observationId);
        }
    }

    public static String journalObservationId(
            String journalBasename,
            long zeroBasedSourceByteOffset
    ) {
        Objects.requireNonNull(journalBasename, "journalBasename");
        if (zeroBasedSourceByteOffset < 0) {
            throw new IllegalArgumentException("zeroBasedSourceByteOffset must be nonnegative");
        }
        MessageDigest digest = sha256();
        digest.update(ID_DOMAIN);
        digest.update(journalBasename.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(zeroBasedSourceByteOffset).getBytes(StandardCharsets.UTF_8));
        return "je1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    }

    private static String fingerprint(String rawJson) {
        return HexFormat.of().formatHex(
                sha256().digest(rawJson.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }

    private record Reservation(
            String journalBasename,
            long sourceByteOffset,
            String rawJsonFingerprint,
            ReservationState state
    ) {

        private boolean samePhysicalRecord(Reservation other) {
            return journalBasename.equals(other.journalBasename)
                    && sourceByteOffset == other.sourceByteOffset
                    && rawJsonFingerprint.equals(other.rawJsonFingerprint);
        }

        private Reservation withState(ReservationState newState) {
            return new Reservation(
                    journalBasename,
                    sourceByteOffset,
                    rawJsonFingerprint,
                    newState
            );
        }
    }

    private enum ReservationState {
        PENDING,
        COMMITTED
    }

    /**
     * Stable source location for a journal record. This transport metadata is
     * deliberately separate from the typed event payload.
     */
    public record JournalSourcePosition(
            String journalBasename,
            long zeroBasedSourceByteOffset
    ) implements kairon.observation.ObservationDraft.SourcePosition {

        public JournalSourcePosition {
            journalBasename = Objects.requireNonNull(
                    journalBasename,
                    "journalBasename"
            );
            if (journalBasename.isBlank()) {
                throw new IllegalArgumentException(
                        "journalBasename must not be blank"
                );
            }
            if (journalBasename.contains("/")
                    || journalBasename.contains("\\")) {
                throw new IllegalArgumentException(
                        "journalBasename must be a basename"
                );
            }
            if (zeroBasedSourceByteOffset < 0) {
                throw new IllegalArgumentException(
                        "zeroBasedSourceByteOffset must be nonnegative"
                );
            }
        }
    }

    public static final class ExactDuplicateJournalObservationException
            extends IllegalStateException {

        public ExactDuplicateJournalObservationException(String observationId) {
            super("duplicate journal observation: " + observationId);
        }
    }

    public static final class ObservationIdentityCollisionException
            extends IllegalStateException {

        public ObservationIdentityCollisionException(String observationId) {
            super("journal observation identity collision: " + observationId);
        }
    }
}
