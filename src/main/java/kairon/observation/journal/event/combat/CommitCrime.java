package kairon.observation.journal.event.combat;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code CommitCrime} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.5</a>
 */
public record CommitCrime(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "CommitCrime";

    public CommitCrime {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A crime was recorded against the Commander.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder record = new StringBuilder(
                "A crime was recorded against the player"
        );
        LlmPresentableJournalEvent.textual(event.get("CrimeType"))
                .ifPresent(type -> record
                        .append(", with crime-type identifier ")
                        .append(LlmPresentableJournalEvent.quoted(type)));
        LlmPresentableJournalEvent.textual(event.get("Faction"))
                .ifPresent(faction -> record
                        .append(", by faction ")
                        .append(LlmPresentableJournalEvent.quoted(faction)));
        LlmPresentableJournalEvent.displayText(event, "Victim")
                .ifPresent(victim -> record
                        .append(", involving victim ")
                        .append(LlmPresentableJournalEvent.quoted(victim)));
        record.append('.');

        List<String> sentences = new ArrayList<>();
        sentences.add(record.toString());
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("Fine"))
                .ifPresent(fine -> sentences.add(
                        "The recorded fine is "
                                + LlmPresentableJournalEvent
                                        .formattedInteger(fine)
                                + " credits."
                ));
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("Bounty"))
                .ifPresent(bounty -> sentences.add(
                        "The recorded bounty is "
                                + LlmPresentableJournalEvent
                                        .formattedInteger(bounty)
                                + " credits."
                ));
        return new LlmEventPresentation(sentences);
    }
}
