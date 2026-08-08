package kairon.observation.journal.event.engineering;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.UnrecognisedEventVariant;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
 * {@code EngineerLegacyConvert} journal event.
 *
 * <p>One wire event, two domain events and a gap. {@code IsPreview}
 * distinguishes the game showing what a conversion <em>would</em> do from the
 * conversion itself, and nothing else in the request carries that distinction —
 * the semantic adapter does not emit the flag. The record used to answer it
 * with a ternary inside its own description, which made it the one place in the
 * system where a class meant two things and only the description knew.</p>
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.14</a>
 */
public sealed interface EngineerLegacyConvert
        extends LlmPresentableJournalEvent {

    String EVENT_TYPE = "EngineerLegacyConvert";

    /** What the conversion itself reports. */
    String CONVERTED_DESCRIPTION =
            "A legacy engineered module was converted to the current format.";

    /** The domain event this record actually is. */
    static EngineerLegacyConvert of(RawJournalData raw) {
        JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        return LlmPresentableJournalEvent
                .booleanValue(raw.parsedJsonObject().get("IsPreview"))
                .<EngineerLegacyConvert>map(preview -> preview
                        ? new Previewed(raw)
                        : new Converted(raw))
                .orElseGet(() -> new Unrecognised(raw));
    }

    /** The game showed what a conversion would do. */
    record Previewed(RawJournalData raw) implements EngineerLegacyConvert {

        public Previewed {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A conversion of a legacy engineered module was previewed.";
        }
    }

    /** The conversion itself. */
    record Converted(RawJournalData raw) implements EngineerLegacyConvert {

        public Converted {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return CONVERTED_DESCRIPTION;
        }
    }

    /**
     * The record carries no usable {@code IsPreview}.
     *
     * <p>It used to say what {@link Converted} says — an absent flag was read
     * as a conversion, first through an {@code orElse(false)} and then, once
     * the class was split, as a written-down constant. Both were a claim the
     * record does not make: a preview and a conversion are the two things this
     * record can be, and one without the flag is neither of them told apart.
     * Saying so is the one thing this variant is for.</p>
     */
    record Unrecognised(RawJournalData raw)
            implements EngineerLegacyConvert, UnrecognisedEventVariant {

        public Unrecognised {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A legacy engineered module conversion or preview was "
                    + "reported without saying which.";
        }
    }

}
