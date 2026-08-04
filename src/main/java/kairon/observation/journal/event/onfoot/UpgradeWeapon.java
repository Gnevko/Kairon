package kairon.observation.journal.event.onfoot;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code UpgradeWeapon} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 12.32</a>
 */
public record UpgradeWeapon(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "UpgradeWeapon";

    public UpgradeWeapon {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String weapon = LlmPresentableJournalEvent.displayText(event, "Name")
                .map(LlmPresentableJournalEvent::quoted)
                .orElse("an unspecified hand weapon");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("Class"))
                .ifPresent(value -> facts.add("new class " + value));
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("Cost"))
                .ifPresent(value -> facts.add(
                        "cost "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
                                + " credits"
                ));
        List<String> sentences = new ArrayList<>();
        sentences.add(
                "The player upgraded hand weapon "
                        + weapon
                        + (facts.isEmpty()
                        ? "."
                        : " with "
                                + LlmPresentableJournalEvent.joinFacts(facts)
                                + ".")
        );
        resources(event.get("Resources")).stream()
                .findFirst()
                .ifPresent(value -> sentences.add(
                        "Resources consumed: " + value + "."
                ));
        return new LlmEventPresentation(sentences);
    }

    private static List<String> resources(JsonNode array) {
        List<String> resources = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return resources;
        }
        for (JsonNode resource : array) {
            if (!resource.isObject()) {
                continue;
            }
            LlmPresentableJournalEvent.displayText(resource, "Name")
                    .ifPresent(name -> {
                        StringBuilder item = new StringBuilder(
                                LlmPresentableJournalEvent.quoted(name)
                        );
                        LlmPresentableJournalEvent
                                .nonNegativeIntegral(resource.get("Count"))
                                .ifPresent(count -> item
                                        .append(" x")
                                        .append(LlmPresentableJournalEvent
                                                .formattedInteger(count)));
                        resources.add(item.toString());
                    });
        }
        return resources.isEmpty()
                ? resources
                : List.of(String.join("; ", resources));
    }
}
