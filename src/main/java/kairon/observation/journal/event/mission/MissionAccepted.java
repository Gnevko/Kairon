package kairon.observation.journal.event.mission;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code MissionAccepted} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.21</a>
 */
public record MissionAccepted(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "MissionAccepted";

    public MissionAccepted {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A mission was accepted.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String mission = LlmPresentableJournalEvent
                .textual(event.get("LocalisedName"))
                .or(() -> LlmPresentableJournalEvent.displayText(
                        event,
                        "Name"
                ))
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unnamed mission");
        List<String> sentences = new ArrayList<>();
        sentences.add("The player accepted and started " + mission + ".");

        List<String> assignment = new ArrayList<>();
        addQuoted(event, "Faction", "offering faction", assignment);
        addQuoted(event, "DestinationSystem", "destination system", assignment);
        addQuoted(
                event,
                "DestinationStation",
                "destination station",
                assignment
        );
        addQuoted(
                event,
                "DestinationSettlement",
                "destination settlement",
                assignment
        );
        addQuoted(event, "Target", "target", assignment);
        addQuoted(event, "TargetFaction", "target faction", assignment);
        LlmPresentableJournalEvent.displayText(event, "Commodity")
                .ifPresent(commodity -> {
                    StringBuilder fact = new StringBuilder("commodity ")
                            .append(LlmPresentableJournalEvent.quoted(
                                    commodity
                            ));
                    LlmPresentableJournalEvent
                            .nonNegativeIntegral(event.get("Count"))
                            .ifPresent(count -> fact
                                    .append(", required count ")
                                    .append(LlmPresentableJournalEvent
                                            .formattedInteger(count)));
                    assignment.add(fact.toString());
                });
        if (!assignment.isEmpty()) {
            sentences.add(
                    "The assignment reports "
                            + LlmPresentableJournalEvent
                            .joinFacts(assignment)
                            + "."
            );
        }

        List<String> terms = new ArrayList<>();
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("Reward"))
                .ifPresent(reward -> terms.add(
                        "expected cash reward "
                                + LlmPresentableJournalEvent
                                .formattedInteger(reward)
                                + " credits"
                ));
        LlmPresentableJournalEvent.booleanValue(event.get("Wing"))
                .ifPresent(wing -> terms.add(
                        wing ? "a wing mission" : "not a wing mission"
                ));
        LlmPresentableJournalEvent.textual(event.get("Expiry"))
                .ifPresent(expiry -> terms.add(
                        "expiry time "
                                + LlmPresentableJournalEvent.quoted(expiry)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MissionID"))
                .ifPresent(id -> terms.add("mission ID " + id));
        if (!terms.isEmpty()) {
            sentences.add(
                    "Its recorded terms include "
                            + LlmPresentableJournalEvent.joinFacts(terms)
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }

    private static void addQuoted(
            JsonNode event,
            String field,
            String label,
            List<String> facts
    ) {
        LlmPresentableJournalEvent.displayText(event, field)
                .ifPresent(value -> facts.add(
                        label + " "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
    }
}
