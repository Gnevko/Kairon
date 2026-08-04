package kairon.observation.journal.event.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;

import java.util.Locale;
import java.util.Optional;

/**
 * Neutral typed identity for the Elite Dangerous {@code Cargo} journal event.
 */
public record Cargo(RawJournalData raw) implements JournalEventObservation {

    public static final String EVENT_TYPE = "Cargo";

    /** The value {@code Vessel} carries when the hold belongs to an SRV. */
    private static final String SRV_VESSEL = "srv";

    public Cargo {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    /**
     * Whether this cargo snapshot belongs to an SRV.
     *
     * <p>The journal tags a cargo snapshot with the vessel whose hold it
     * describes. That is weaker evidence than it looks: it says which vessel the
     * hold belongs to, not where the Commander is, and callers must not read it
     * as occupancy.</p>
     *
     * <p>Empty when the event carries no {@code Vessel} at all — older records
     * and some snapshots omit it — which is an absence of evidence and never a
     * claim that the vessel is something else. The comparison is
     * locale-independent so that a Turkish default locale cannot lowercase
     * {@code SRV} into something that stops matching.</p>
     */
    public Optional<Boolean> optionalSrvVessel() {
        JsonNode vessel = raw.parsedJsonObject().get("Vessel");
        if (vessel == null || !vessel.isTextual()) {
            return Optional.empty();
        }
        String value = vessel.textValue().strip();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                SRV_VESSEL.equals(value.toLowerCase(Locale.ROOT))
        );
    }
}
