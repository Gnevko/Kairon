package kairon.observation.journal.event.travel;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.UnrecognisedEventVariant;

import static kairon.observation.journal.LlmPresentableJournalEvent.textual;

/**
 * Neutral typed identity for the Elite Dangerous {@code StartJump} journal
 * event.
 *
 * <p>One wire event, two domain events. {@code JumpType} says whether the ship
 * is charging for another star system or for supercruise, and those are
 * different things to have started — one leaves the system, the other does not.
 * The dispatch happens once, here, at parse time.</p>
 */
public sealed interface StartJump extends JournalEventObservation {

    String EVENT_TYPE = "StartJump";

    /** The domain event this record actually is. */
    static StartJump of(RawJournalData raw) {
        JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        return switch (textual(raw.parsedJsonObject().get("JumpType"))
                .orElse("")) {
            case "Hyperspace" -> new Hyperspace(raw);
            case "Supercruise" -> new Supercruise(raw);
            default -> new Unrecognised(raw);
        };
    }

    /** The frame shift drive is charging for another star system. */
    record Hyperspace(RawJournalData raw) implements StartJump {

        public Hyperspace {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }
    }

    /** The frame shift drive is charging for supercruise. */
    record Supercruise(RawJournalData raw) implements StartJump {

        public Supercruise {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }
    }

    /** A {@code JumpType} this build does not recognise. */
    record Unrecognised(RawJournalData raw)
            implements StartJump, UnrecognisedEventVariant {

        public Unrecognised {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }
    }
}
