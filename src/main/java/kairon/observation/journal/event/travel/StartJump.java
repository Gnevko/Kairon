package kairon.observation.journal.event.travel;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.UnrecognisedEventVariant;

import java.util.ArrayList;
import java.util.List;

import static kairon.observation.journal.LlmPresentableJournalEvent.displayText;
import static kairon.observation.journal.LlmPresentableJournalEvent.quoted;
import static kairon.observation.journal.LlmPresentableJournalEvent.textual;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code StartJump} journal event.
 *
 * <p>One wire event, two domain events. {@code JumpType} says whether the ship
 * is charging for another star system or for supercruise, and those are
 * different things to have started — one leaves the system, the other does not.
 * The dispatch happens once, here, at parse time.</p>
 *
 * <p>Neither charge is a model-eligible trigger: starting one opens no turn.
 * They describe themselves anyway because the behaviour graph records them as
 * {@code HYPERSPACE_JUMP_STARTED} and {@code SUPERCRUISE_JUMP_STARTED}, and a
 * graph vertex can reach the model as a remembered predecessor. A vertex the
 * model can be shown has to be able to say what it is, and saying it here
 * rather than in the trajectory table is what lets a test compare the two.</p>
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.7</a>
 */
public sealed interface StartJump extends LlmPresentableJournalEvent {

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

        @Override
        public String modelFacingDescription() {
            return "The Commander's ship began charging its frame shift "
                    + "drive for a jump to another star system.";
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return charge(raw, "The frame shift drive began charging for a "
                    + "jump to another star system.");
        }
    }

    /** The frame shift drive is charging for supercruise. */
    record Supercruise(RawJournalData raw) implements StartJump {

        public Supercruise {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "The Commander's ship began charging its frame shift "
                    + "drive for supercruise.";
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return charge(raw, "The frame shift drive began charging for "
                    + "supercruise.");
        }
    }

    /**
     * A {@code JumpType} this build does not recognise.
     *
     * <p>Reported as a charge without claiming which kind: the record says the
     * drive started and Kairon does not know what for. It reaches no
     * trajectory — an unrecognised discriminator normalizes to an
     * {@code UNKNOWN_*} type, which is dropped rather than spelled out — so
     * this sentence exists for the contract rather than for a reader.</p>
     */
    record Unrecognised(RawJournalData raw)
            implements StartJump, UnrecognisedEventVariant {

        public Unrecognised {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "The Commander's ship began charging its frame shift "
                    + "drive for a jump of an unidentified kind.";
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            JsonNode event = raw.parsedJsonObject();
            String kind = textual(event.get("JumpType")).orElse("");
            return charge(raw, kind.isEmpty()
                    ? "The frame shift drive began charging, and the journal "
                            + "did not report what for."
                    : "The frame shift drive began charging for a jump the "
                            + "journal reports as type " + quoted(kind) + ".");
        }
    }

    // ----------------------------------------------------------- presentation

    /**
     * The charge, plus the destination the record names when it names one.
     *
     * <p>A hyperspace charge carries the target system and the class of the
     * star it will arrive at; a supercruise charge carries neither. Nothing is
     * asserted about arrival: the drive has started, and that is all this
     * record establishes.</p>
     */
    private static LlmEventPresentation charge(
            RawJournalData raw,
            String lead
    ) {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();
        sentences.add(lead);

        List<String> facts = new ArrayList<>();
        displayText(event, "StarSystem")
                .ifPresent(system -> facts.add(
                        "target system " + quoted(system)
                ));
        displayText(event, "StarClass")
                .ifPresent(starClass -> facts.add(
                        "stellar classification " + quoted(starClass)
                                + " at that destination"
                ));
        if (!facts.isEmpty()) {
            sentences.add(
                    "The charge record reports "
                            + LlmPresentableJournalEvent.joinFacts(facts)
                            + "; it does not establish that the jump completed."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
