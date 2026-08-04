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
 * {@code ColonisationSystemClaim} journal event.
 *
 * @see <a href="https://schemas.edomh.nl/ColonisationSystemClaim.html">
 * Pinned journal-catalogue event contract</a>
 */
public record ColonisationSystemClaim(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ColonisationSystemClaim";

    public ColonisationSystemClaim {
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
                "The player paid to claim a star system for colonisation"
        );
        if (!facts.isEmpty()) {
            sentence.append(": ")
                    .append(LlmPresentableJournalEvent.joinFacts(facts));
        }
        sentence.append("; this event does not report that construction has "
                + "started.");
        return new LlmEventPresentation(List.of(sentence.toString()));
    }
}
