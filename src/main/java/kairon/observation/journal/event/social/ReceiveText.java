package kairon.observation.journal.event.social;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;

/**
 * Typed identity and model-facing sentence for the Elite Dangerous
 * {@code ReceiveText} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.36</a>
 */
public record ReceiveText(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ReceiveText";

    public ReceiveText {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    /**
     * The only event whose content is written by a person. Everything else the
     * model is shown was built here; this one carries a stranger's words, so
     * the sentence says whose they are and where they were said, and the
     * channel and sender fields beside it name which player and which channel.
     */
    @Override
    public String modelFacingDescription() {
        return "Another player sent a text message to a channel the Commander "
                + "is in.";
    }
}
