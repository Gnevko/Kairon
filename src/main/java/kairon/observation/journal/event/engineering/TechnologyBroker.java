package kairon.observation.journal.event.engineering;

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
 * {@code TechnologyBroker} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.54</a>
 */
public record TechnologyBroker(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "TechnologyBroker";

    public TechnologyBroker {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder unlock = new StringBuilder(
                "The player used a technology broker to unlock technology "
                        + "that can now be purchased"
        );
        LlmPresentableJournalEvent.textual(event.get("BrokerType"))
                .ifPresent(type -> unlock
                        .append(" from broker type ")
                        .append(LlmPresentableJournalEvent.quoted(type)));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(marketId -> unlock
                        .append(" at market ID ")
                        .append(marketId));
        unlock.append('.');

        List<String> sentences = new ArrayList<>();
        sentences.add(unlock.toString());
        namedEntries(event.get("ItemsUnlocked"), false)
                .ifPresent(items -> sentences.add(
                        "Items unlocked: " + items + "."
                ));
        namedEntries(event.get("Commodities"), true)
                .ifPresent(items -> sentences.add(
                        "Commodities used: " + items + "."
                ));
        namedEntries(event.get("Materials"), true)
                .ifPresent(items -> sentences.add(
                        "Materials used: " + items + "."
                ));
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> namedEntries(
            JsonNode values,
            boolean includeCount
    ) {
        if (values == null || !values.isArray()) {
            return Optional.empty();
        }
        List<String> entries = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isObject()) {
                continue;
            }
            Optional<String> name =
                    LlmPresentableJournalEvent.displayText(value, "Name");
            if (name.isEmpty()) {
                continue;
            }
            StringBuilder entry = new StringBuilder(
                    LlmPresentableJournalEvent.quoted(name.get())
            );
            if (includeCount) {
                LlmPresentableJournalEvent
                        .nonNegativeIntegral(value.get("Count"))
                        .ifPresent(count -> entry
                                .append(" x ")
                                .append(LlmPresentableJournalEvent
                                        .formattedInteger(count)));
            }
            LlmPresentableJournalEvent.textual(value.get("Category"))
                    .ifPresent(category -> entry
                            .append(" (category ")
                            .append(LlmPresentableJournalEvent.quoted(category))
                            .append(')'));
            entries.add(entry.toString());
        }
        return entries.isEmpty()
                ? Optional.empty()
                : Optional.of(String.join("; ", entries));
    }
}
