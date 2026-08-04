package kairon.observation.journal.event.mission;

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
 * {@code MissionCompleted} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.22</a>
 */
public record MissionCompleted(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "MissionCompleted";

    public MissionCompleted {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
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
        sentences.add("The player completed " + mission + ".");

        List<String> outcome = new ArrayList<>();
        addQuoted(event, "Faction", "issuing faction", outcome);
        addQuoted(event, "DestinationSystem", "destination system", outcome);
        addQuoted(event, "DestinationStation", "destination station", outcome);
        addQuoted(
                event,
                "DestinationSettlement",
                "destination settlement",
                outcome
        );
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("Reward"))
                .ifPresent(reward -> outcome.add(
                        "cash reward "
                                + LlmPresentableJournalEvent
                                .formattedInteger(reward)
                                + " credits"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MissionID"))
                .ifPresent(id -> outcome.add("mission ID " + id));
        if (!outcome.isEmpty()) {
            sentences.add(
                    "The completion record reports "
                            + LlmPresentableJournalEvent.joinFacts(outcome)
                            + "."
            );
        }

        List<String> rewards = new ArrayList<>();
        arrayItems(event.get("CommodityReward"), false)
                .ifPresent(value -> rewards.add("commodities: " + value));
        arrayItems(event.get("MaterialsReward"), true)
                .ifPresent(value -> rewards.add("materials: " + value));
        quotedArray(event.get("PermitsAwarded"))
                .ifPresent(value -> rewards.add("permits: " + value));
        if (!rewards.isEmpty()) {
            sentences.add(
                    "Additional rewards are "
                            + LlmPresentableJournalEvent.joinFacts(rewards)
                            + "."
            );
        }
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> arrayItems(
            JsonNode array,
            boolean includeCategory
    ) {
        if (array == null || !array.isArray()) {
            return Optional.empty();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isObject()) {
                continue;
            }
            LlmPresentableJournalEvent.displayText(item, "Name")
                    .ifPresent(name -> {
                        StringBuilder value = new StringBuilder(
                                LlmPresentableJournalEvent.quoted(name)
                        );
                        LlmPresentableJournalEvent
                                .nonNegativeIntegral(item.get("Count"))
                                .ifPresent(count -> value
                                        .append(" x")
                                        .append(LlmPresentableJournalEvent
                                                .formattedInteger(count)));
                        if (includeCategory) {
                            LlmPresentableJournalEvent
                                    .displayText(item, "Category")
                                    .ifPresent(category -> value
                                            .append(" (")
                                            .append(category)
                                            .append(')'));
                        }
                        items.add(value.toString());
                    });
        }
        return items.isEmpty()
                ? Optional.empty()
                : Optional.of(String.join("; ", items));
    }

    private static Optional<String> quotedArray(JsonNode array) {
        if (array == null || !array.isArray()) {
            return Optional.empty();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            LlmPresentableJournalEvent.textual(item).ifPresent(value ->
                    values.add(LlmPresentableJournalEvent.quoted(value))
            );
        }
        return values.isEmpty()
                ? Optional.empty()
                : Optional.of(String.join("; ", values));
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
