package kairon.observation.journal.event.combat;

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
 * {@code Died} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, sections 5.3 and 5.4</a>
 */
public record Died(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Died";

    public Died {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "The Commander was killed.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();
        sentences.add("The player was killed.");
        singleKiller(event).ifPresent(sentences::add);
        wingKillers(event.get("Killers")).ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> singleKiller(JsonNode event) {
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.displayText(event, "KillerName")
                .ifPresent(name -> facts.add(
                        "name " + LlmPresentableJournalEvent.quoted(name)
                ));
        LlmPresentableJournalEvent.displayText(event, "KillerShip")
                .ifPresent(ship -> facts.add(
                        "ship type "
                                + LlmPresentableJournalEvent.quoted(ship)
                ));
        LlmPresentableJournalEvent.textual(event.get("KillerRank"))
                .ifPresent(rank -> facts.add(
                        "combat rank "
                                + LlmPresentableJournalEvent.quoted(rank)
                ));
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The recorded killer has "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + "."
        );
    }

    private static Optional<String> wingKillers(JsonNode killers) {
        if (killers == null || !killers.isArray()) {
            return Optional.empty();
        }
        List<String> members = new ArrayList<>();
        for (JsonNode killer : killers) {
            if (!killer.isObject()) {
                continue;
            }
            List<String> facts = new ArrayList<>();
            LlmPresentableJournalEvent.displayText(killer, "Name")
                    .ifPresent(name -> facts.add(
                            "name "
                                    + LlmPresentableJournalEvent.quoted(name)
                    ));
            LlmPresentableJournalEvent.displayText(killer, "Ship")
                    .ifPresent(ship -> facts.add(
                            "ship type "
                                    + LlmPresentableJournalEvent.quoted(ship)
                    ));
            LlmPresentableJournalEvent.textual(killer.get("Rank"))
                    .ifPresent(rank -> facts.add(
                            "combat rank "
                                    + LlmPresentableJournalEvent.quoted(rank)
                    ));
            if (!facts.isEmpty()) {
                members.add(
                        LlmPresentableJournalEvent.joinFacts(facts)
                );
            }
        }
        if (members.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The journal lists the killer wing members as "
                        + String.join("; ", members)
                        + "."
        );
    }
}
