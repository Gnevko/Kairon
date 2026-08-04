package kairon.observation.journal.event.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code SellOrganicData} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 12.24</a>
 */
public record SellOrganicData(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "SellOrganicData";

    public SellOrganicData {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "Organic data was sold.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder sale = new StringBuilder(
                "The player sold organic data"
        );
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(marketId -> sale
                        .append(" at market ID ")
                        .append(marketId));
        sale.append('.');

        List<String> sentences = new ArrayList<>();
        sentences.add(sale.toString());
        bioData(event.get("BioData")).ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> bioData(JsonNode values) {
        if (values == null || !values.isArray()) {
            return Optional.empty();
        }
        List<String> entries = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isObject()) {
                continue;
            }
            List<String> identity = new ArrayList<>();
            addDisplay(value, "Genus", "genus", identity);
            addDisplay(value, "Species", "species", identity);
            addDisplay(value, "Variant", "variant", identity);
            List<String> earnings = new ArrayList<>();
            addCredits(value, "Value", "base value", earnings);
            addCredits(value, "Bonus", "bonus", earnings);
            if (!identity.isEmpty() || !earnings.isEmpty()) {
                StringBuilder entry = new StringBuilder();
                if (!identity.isEmpty()) {
                    entry.append(LlmPresentableJournalEvent.joinFacts(
                            identity
                    ));
                }
                if (!earnings.isEmpty()) {
                    if (!identity.isEmpty()) {
                        entry.append(", with ");
                    }
                    entry.append(LlmPresentableJournalEvent.joinFacts(
                            earnings
                    ));
                }
                entries.add(entry.toString());
            }
        }
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "Organic data sold: "
                        + String.join("; ", entries)
                        + "."
        );
    }

    private static void addDisplay(
            JsonNode event,
            String field,
            String label,
            List<String> facts
    ) {
        LlmPresentableJournalEvent.displayText(event, field)
                .ifPresent(value -> facts.add(
                        label + " " + LlmPresentableJournalEvent.quoted(value)
                ));
    }

    private static void addCredits(
            JsonNode event,
            String field,
            String label,
            List<String> facts
    ) {
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get(field))
                .ifPresent(value -> facts.add(
                        label
                                + " "
                                + LlmPresentableJournalEvent.formattedInteger(
                                        value
                                )
                                + " credits"
                ));
    }
}
