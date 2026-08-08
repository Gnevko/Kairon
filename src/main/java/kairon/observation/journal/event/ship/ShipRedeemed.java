package kairon.observation.journal.event.ship;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
 * {@code ShipRedeemed} journal event.
 *
 * @see <a href="https://github.com/jixxed/ed-journal-schemas/blob/33a8f35e81868b168b4bbd647b5e13dbd8de062a/schemas/ShipRedeemed/ShipRedeemed.json">
 * Pinned Elite Dangerous journal schema for ShipRedeemed</a>
 */
public record ShipRedeemed(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ShipRedeemed";

    public ShipRedeemed {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A new ship was redeemed.";
    }
}
