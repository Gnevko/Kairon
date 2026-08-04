package kairon.behavior.graph;

import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.model.TransitionOccurrenceId;
import kairon.behavior.model.EpisodeEntrySource;
import kairon.behavior.normalize.NormalizedEventType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * Domain-separated deterministic IDs for values not already identified by a
 * journal observation envelope.
 */
public final class BehaviorGraphIds {

    private BehaviorGraphIds() {
    }

    public static EventOccurrenceId journalOccurrence(
            GraphId graphId,
            String observationId
    ) {
        return new EventOccurrenceId(hash(
                "kairon-behavior-journal-occurrence-v1",
                "bgo1-",
                Objects.requireNonNull(graphId, "graphId").canonicalValue(),
                Objects.requireNonNull(observationId, "observationId")
        ));
    }

    public static EventOccurrenceId shipSwitchOccurrence(
            String triggeringObservationId,
            GraphId graphId
    ) {
        return new EventOccurrenceId(hash(
                "kairon-behavior-ship-switch-occurrence-v1",
                "bgo1-",
                triggeringObservationId,
                graphId.canonicalValue()
        ));
    }

    public static EventOccurrenceId statusOccurrence(
            GraphId graphId,
            String statusObservationId,
            NormalizedEventType eventType
    ) {
        return new EventOccurrenceId(hash(
                "kairon-behavior-status-occurrence-v1",
                "bgo1-",
                Objects.requireNonNull(graphId, "graphId").canonicalValue(),
                Objects.requireNonNull(
                        statusObservationId,
                        "statusObservationId"
                ),
                Objects.requireNonNull(eventType, "eventType").value()
        ));
    }

    public static SystemEpisodeId episode(
            GraphId graphId,
            EventOccurrenceId rootOccurrenceId,
            EpisodeEntrySource entrySource
    ) {
        return new SystemEpisodeId(hash(
                "kairon-behavior-system-episode-v1",
                "bge1-",
                graphId.canonicalValue(),
                rootOccurrenceId.value(),
                entrySource.name()
        ));
    }

    /**
     * The episode a restoring {@code Location} opens.
     *
     * <p>Keyed on the restoring observation rather than on a root occurrence,
     * because a restored episode has none: nothing happened, so nothing was
     * recorded. Replaying the same journal mints the same id, which is what
     * makes the restore idempotent.</p>
     */
    public static SystemEpisodeId restoredEpisode(
            GraphId graphId,
            String observationId
    ) {
        return new SystemEpisodeId(hash(
                "kairon-behavior-restored-system-episode-v1",
                "bge1-",
                Objects.requireNonNull(graphId, "graphId").canonicalValue(),
                Objects.requireNonNull(observationId, "observationId"),
                EpisodeEntrySource.LOCATION_RESTORE.name()
        ));
    }

    public static TransitionOccurrenceId transition(
            SystemEpisodeId episodeId,
            EventOccurrenceId fromOccurrenceId,
            EventOccurrenceId toOccurrenceId
    ) {
        return new TransitionOccurrenceId(hash(
                "kairon-behavior-occurrence-transition-v1",
                "bgt1-",
                episodeId.value(),
                fromOccurrenceId.value(),
                toOccurrenceId.value()
        ));
    }

    private static String hash(
            String domain,
            String prefix,
            String... values
    ) {
        MessageDigest digest = sha256();
        digest.update(domain.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        for (String value : values) {
            digest.update(Objects.requireNonNull(value, "ID component")
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return prefix + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(digest.digest());
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
}
