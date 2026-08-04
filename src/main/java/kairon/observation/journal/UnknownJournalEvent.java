package kairon.observation.journal;

import kairon.observation.journal.JournalEventObservation.RawJournalData;

import java.util.Objects;

/**
 * Forward-compatible payload for a missing or not-yet-catalogued event discriminator.
 */
public record UnknownJournalEvent(RawJournalData raw)
        implements JournalEventObservation {

    public UnknownJournalEvent {
        raw = Objects.requireNonNull(raw, "raw");
        if (raw.optionalEventType()
                .filter(JournalEventCatalog::isKnownEventType)
                .isPresent()) {
            throw new IllegalArgumentException(
                    "UnknownJournalEvent cannot wrap a pinned journal event discriminator"
            );
        }
    }
}