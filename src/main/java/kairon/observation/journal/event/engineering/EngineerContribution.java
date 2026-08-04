package kairon.observation.journal.event.engineering;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code EngineerContribution} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.12</a>
 */
public record EngineerContribution(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "EngineerContribution";

    public EngineerContribution {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "Items, cash or bounties were offered to an engineer to gain access.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder contribution = new StringBuilder(
                "The player made a contribution to gain access to an "
                        + "engineer"
        );
        LlmPresentableJournalEvent.textual(event.get("Engineer"))
                .ifPresent(engineer -> contribution
                        .append(", ")
                        .append(LlmPresentableJournalEvent.quoted(engineer)));
        contribution.append('.');

        List<String> sentences = new ArrayList<>();
        sentences.add(contribution.toString());
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.textual(event.get("Type"))
                .ifPresent(type -> facts.add(
                        "contribution type "
                                + LlmPresentableJournalEvent.quoted(type)
                ));
        LlmPresentableJournalEvent.displayText(event, "Commodity")
                .ifPresent(commodity -> facts.add(
                        "commodity "
                                + LlmPresentableJournalEvent.quoted(commodity)
                ));
        LlmPresentableJournalEvent.displayText(event, "Material")
                .ifPresent(material -> facts.add(
                        "material "
                                + LlmPresentableJournalEvent.quoted(material)
                ));
        LlmPresentableJournalEvent.textual(event.get("Faction"))
                .ifPresent(faction -> facts.add(
                        "faction "
                                + LlmPresentableJournalEvent.quoted(faction)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("Quantity"))
                .ifPresent(quantity -> facts.add(
                        "amount offered now "
                                + LlmPresentableJournalEvent
                                        .formattedInteger(quantity)
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("TotalQuantity"))
                .ifPresent(total -> facts.add(
                        "total donated "
                                + LlmPresentableJournalEvent
                                        .formattedInteger(total)
                ));
        if (!facts.isEmpty()) {
            sentences.add(
                    "The contribution record reports "
                            + LlmPresentableJournalEvent.joinFacts(facts)
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }
}
