package kairon.semantics;

import kairon.observation.ObservationPayload;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.status.StatusSnapshotObservation;

import java.util.Objects;

/**
 * Resolves the source role of an observation from the observation itself.
 *
 * <p>Journal classification reads {@link SemanticSourceRoleCatalog}, which is
 * the one place a journal type's role is declared. It used to read the
 * observer's selection profile instead, which made the meaning of a record
 * depend on the consumer that happened to be built first — and made this
 * package import the observer.</p>
 *
 * <p>The role is a property of the observation. It is never inferred after the
 * fact from whether the observation reached a trigger queue.</p>
 */
public final class SemanticSourceRoles {

    private SemanticSourceRoles() {
    }

    public static SemanticSourceRole roleOf(ObservationPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload instanceof JournalEventObservation event) {
            return SemanticSourceRoleCatalog.roleOf(event.getClass());
        }
        if (payload instanceof StatusSnapshotObservation) {
            return SemanticSourceRole.STATUS;
        }
        if (payload instanceof ObservationSourceSignal) {
            return SemanticSourceRole.CONTROL;
        }
        return SemanticSourceRole.DIAGNOSTIC_ONLY;
    }

    /**
     * The raw observation type used for provenance.
     *
     * <p>Journal events report their canonical journal name; non-journal
     * observations report a stable observation-kind token.</p>
     */
    public static String rawObservationTypeOf(ObservationPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload instanceof JournalEventObservation event) {
            return event.raw()
                    .optionalEventType()
                    .orElse("UnknownJournalEvent");
        }
        if (payload instanceof StatusSnapshotObservation) {
            return "Status";
        }
        if (payload instanceof ObservationSourceSignal signal) {
            return signal.signalType().name();
        }
        return payload.getClass().getSimpleName();
    }
}
