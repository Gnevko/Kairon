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
 * {@code MultiSellExplorationData} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.11</a>
 */
public record MultiSellExplorationData(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "MultiSellExplorationData";

    public MultiSellExplorationData {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A page of exploration data was sold at Cartographics.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();
        sentences.add(
                "The player sold one page of exploration data at "
                        + "Cartographics."
        );
        discoveredSystems(event.get("Discovered")).ifPresent(systems ->
                sentences.add("Systems sold: " + systems + ".")
        );
        earnings(event).ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> discoveredSystems(JsonNode discovered) {
        if (discovered == null || !discovered.isArray()) {
            return Optional.empty();
        }
        List<String> systems = new ArrayList<>();
        for (JsonNode entry : discovered) {
            if (!entry.isObject()) {
                continue;
            }
            Optional<String> name = LlmPresentableJournalEvent.textual(
                    entry.get("SystemName")
            );
            if (name.isEmpty()) {
                continue;
            }
            StringBuilder system = new StringBuilder(
                    LlmPresentableJournalEvent.quoted(name.get())
            );
            LlmPresentableJournalEvent
                    .nonNegativeIntegral(entry.get("NumBodies"))
                    .ifPresent(count -> system
                            .append(" with ")
                            .append(LlmPresentableJournalEvent
                                    .formattedInteger(count))
                            .append(" bod")
                            .append(count == 1 ? "y" : "ies"));
            systems.add(system.toString());
        }
        return systems.isEmpty()
                ? Optional.empty()
                : Optional.of(String.join("; ", systems));
    }

    private static Optional<String> earnings(JsonNode event) {
        List<String> facts = new ArrayList<>();
        addCredits(event, "BaseValue", "base value", facts);
        addCredits(event, "Bonus", "first-discovery bonus", facts);
        addCredits(event, "TotalEarnings", "total earnings", facts);
        return facts.isEmpty()
                ? Optional.empty()
                : Optional.of(
                        "The sale reports "
                                + LlmPresentableJournalEvent.joinFacts(facts)
                                + "."
                );
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
