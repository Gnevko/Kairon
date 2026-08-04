package kairon.observation.journal.event.colonisation;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code ColonisationSystemClaimRelease} journal event.
 *
 * @see <a href="https://schemas.edomh.nl/ColonisationSystemClaimRelease.html">
 * Pinned journal-catalogue event contract</a>
 */
public record ColonisationSystemClaimRelease(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ColonisationSystemClaimRelease";

    public ColonisationSystemClaimRelease {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("StarSystem"))
                .ifPresent(system -> facts.add(
                        "star system "
                                + LlmPresentableJournalEvent.quoted(system)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("SystemAddress"))
                .ifPresent(address -> facts.add(
                        "star-system address " + address
                ));

        StringBuilder sentence = new StringBuilder(
                "A colonisation claim was released"
        );
        if (!facts.isEmpty()) {
            sentence.append(" for ")
                    .append(LlmPresentableJournalEvent.joinFacts(facts));
        }
        sentence.append("; this event does not report the reason or any "
                + "refund.");
        return new LlmEventPresentation(List.of(sentence.toString()));
    }
}
