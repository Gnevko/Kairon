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
 * {@code SellExplorationData} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.17</a>
 */
public record SellExplorationData(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "SellExplorationData";

    public SellExplorationData {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();
        sentences.add("The player sold exploration data at Cartographics.");
        stringArray(event.get("Systems")).ifPresent(systems ->
                sentences.add("Systems in the sale: " + systems + ".")
        );
        stringArray(event.get("Discovered")).ifPresent(bodies ->
                sentences.add(
                        "Bodies listed as first discoveries: "
                                + bodies
                                + "."
                )
        );

        List<String> earnings = new ArrayList<>();
        addCredits(event, "BaseValue", "system base value", earnings);
        addCredits(event, "Bonus", "first-discovery bonus", earnings);
        addCredits(event, "TotalEarnings", "total credits received", earnings);
        if (!earnings.isEmpty()) {
            sentences.add(
                    "The sale reports "
                            + LlmPresentableJournalEvent.joinFacts(earnings)
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> stringArray(JsonNode values) {
        if (values == null || !values.isArray()) {
            return Optional.empty();
        }
        List<String> entries = new ArrayList<>();
        for (JsonNode value : values) {
            LlmPresentableJournalEvent.textual(value)
                    .ifPresent(text -> entries.add(
                            LlmPresentableJournalEvent.quoted(text)
                    ));
        }
        return entries.isEmpty()
                ? Optional.empty()
                : Optional.of(String.join("; ", entries));
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
