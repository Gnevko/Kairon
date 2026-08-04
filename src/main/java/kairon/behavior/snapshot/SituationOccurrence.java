package kairon.behavior.snapshot;

import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.EventOccurrenceSource;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.semantics.BodyIdentity;

import java.time.Instant;
import java.util.Objects;

/**
 * Compact immutable occurrence identity in an active episode trajectory.
 *
 * <p>{@code source} is carried straight from the accepted occurrence. It is
 * never re-derived from {@code eventType}: a journal event and a Status
 * snapshot can normalize to the same type. {@code null} means the occurrence
 * was restored from persistence, which does not carry provenance.</p>
 *
 * <p>{@code body} is carried the same way, from the context the graph recorded
 * with the occurrence. It is the projection's answer to "which body did this
 * happen at", and it exists so that a repeated event can be counted against one
 * body rather than against a whole system visit. {@code null} means the graph
 * had not established a body when it accepted the occurrence — never that the
 * occurrence happened nowhere, and never an invitation to work it out from a
 * name.</p>
 *
 * <p>It is a {@link BodyIdentity}, the same value a scanner reading and the
 * canonical body registry are keyed by. This used to be a record of its own
 * with the same two fields, which meant "the body this occurrence happened at"
 * and "the body this reading is about" could not be compared without one of
 * them being rebuilt as the other.</p>
 */
public record SituationOccurrence(
        EventOccurrenceId occurrenceId,
        long episodeSequence,
        NormalizedEventType eventType,
        EventOccurrenceSource source,
        Instant occurredAt,
        boolean current,
        BodyIdentity body
) {

    public SituationOccurrence {
        occurrenceId = Objects.requireNonNull(
                occurrenceId,
                "occurrenceId"
        );
        if (episodeSequence < 0) {
            throw new IllegalArgumentException(
                    "episodeSequence must be nonnegative"
            );
        }
        eventType = Objects.requireNonNull(eventType, "eventType");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
