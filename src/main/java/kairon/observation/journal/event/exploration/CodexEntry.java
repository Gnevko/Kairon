package kairon.observation.journal.event.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static kairon.observation.journal.LlmPresentableJournalEvent.decimal;
import static kairon.observation.journal.LlmPresentableJournalEvent.displayText;
import static kairon.observation.journal.LlmPresentableJournalEvent.quoted;
import static kairon.observation.journal.LlmPresentableJournalEvent.textual;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code CodexEntry} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.1</a>
 */
public record CodexEntry(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "CodexEntry";

    public CodexEntry {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();

        StringBuilder identity = new StringBuilder(
                "The Codex recorded an entry"
        );
        displayText(event, "Name")
                .ifPresent(name -> identity
                        .append(" for ")
                        .append(quoted(name)));
        integral(event.get("EntryID"))
                .ifPresent(entryId -> identity
                        .append(", entry ID ")
                        .append(entryId));
        identity.append('.');
        sentences.add(identity.toString());

        classification(event).ifPresent(sentences::add);
        location(event).ifPresent(sentences::add);
        codexFlags(event).ifPresent(sentences::add);
        traits(event).ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> classification(JsonNode event) {
        List<String> facts = new ArrayList<>();
        displayText(event, "Category")
                .ifPresent(value -> facts.add(
                        "category " + quoted(value)
                ));
        displayText(event, "SubCategory")
                .ifPresent(value -> facts.add(
                        "subcategory " + quoted(value)
                ));
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The entry has " + joinFacts(facts) + "."
        );
    }

    private static Optional<String> location(JsonNode event) {
        List<String> facts = new ArrayList<>();
        displayText(event, "Region")
                .ifPresent(value -> facts.add(
                        "region " + quoted(value)
                ));
        displayText(event, "System")
                .ifPresent(value -> facts.add(
                        "system " + quoted(value)
                ));
        integral(event.get("BodyID"))
                .ifPresent(value -> facts.add("body ID " + value));
        displayText(event, "NearestDestination")
                .ifPresent(value -> facts.add(
                        "nearest listed navigation-panel location "
                                + quoted(value)
                ));
        decimal(event.get("Latitude"))
                .ifPresent(value -> facts.add("latitude " + value));
        decimal(event.get("Longitude"))
                .ifPresent(value -> facts.add("longitude " + value));
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The journal locates it at " + joinFacts(facts) + "."
        );
    }

    private static Optional<String> codexFlags(JsonNode event) {
        List<String> facts = new ArrayList<>();
        booleanValue(event.get("IsNewEntry"))
                .ifPresent(value -> facts.add(
                        value
                                ? "this is a new Codex entry"
                                : "this is not a new Codex entry"
                ));
        booleanValue(event.get("NewTraitsDiscovered"))
                .ifPresent(value -> facts.add(
                        value
                                ? "new traits were discovered"
                                : "no new traits were discovered"
                ));
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The journal explicitly reports that "
                        + joinFacts(facts)
                        + "."
        );
    }

    private static Optional<String> traits(JsonNode event) {
        JsonNode traits = event.get("Traits");
        if (traits == null || !traits.isArray()) {
            return Optional.empty();
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode trait : traits) {
            textual(trait)
                    .filter(value ->
                            !(value.startsWith("$") && value.endsWith(";")))
                    .map(LlmPresentableJournalEvent::quoted)
                    .ifPresent(labels::add);
        }
        if (labels.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The journal lists these unlocked traits: "
                        + String.join(", ", labels)
                        + "."
        );
    }

    private static Optional<Boolean> booleanValue(JsonNode value) {
        return value != null && value.isBoolean()
                ? Optional.of(value.booleanValue())
                : Optional.empty();
    }

    private static Optional<Long> integral(JsonNode value) {
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToLong()
                && value.longValue() >= 0
                ? Optional.of(value.longValue())
                : Optional.empty();
    }

    private static String joinFacts(List<String> facts) {
        if (facts.size() == 1) {
            return facts.getFirst();
        }
        if (facts.size() == 2) {
            return facts.getFirst() + " and " + facts.getLast();
        }
        return String.join(", ", facts.subList(0, facts.size() - 1))
                + ", and "
                + facts.getLast();
    }
}
